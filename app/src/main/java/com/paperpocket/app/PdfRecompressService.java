package com.paperpocket.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Rasterizes one document in a fresh isolated process, using only transferred descriptors. */
public final class PdfRecompressService extends Service {
    static final int START = 1, PROGRESS = 2, RESULT = 3, FINISH = 4;
    private static final long MAX_RENDER_PIXELS = 12_000_000L;
    private static final int MAX_JPEG_BYTES = 32 * 1024 * 1024;
    private final Handler handler = new Handler(Looper.getMainLooper(), this::receive);
    private final Messenger endpoint = new Messenger(handler);
    private boolean accepted;

    @Override public IBinder onBind(Intent intent) {
        // Never parse PDFs or terminate a process if the isolation manifest entry is missing.
        return Process.myUid() != getApplicationInfo().uid ? endpoint.getBinder() : null;
    }

    private boolean receive(Message request) {
        if (request.what == FINISH) { terminateChild(); return true; }
        if (request.what != START || accepted || request.replyTo == null) return false;
        accepted = true;
        Bundle data = request.getData();
        data.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
        ParcelFileDescriptor input = data.getParcelable("input");
        ParcelFileDescriptor output = data.getParcelable("output");
        int ordinal = data.getInt("profile", PdfConverter.Profile.BALANCED.ordinal());
        PdfConverter.Profile[] profiles = PdfConverter.Profile.values();
        PdfConverter.Profile profile = ordinal >= 0 && ordinal < profiles.length
                ? profiles[ordinal] : PdfConverter.Profile.BALANCED;
        Messenger reply = request.replyTo;
        handler.postDelayed(this::terminateChild, 15 * 60_000L);
        new Thread(() -> {
            int pages = -1;
            String error = null;
            try (ParcelFileDescriptor source = input; ParcelFileDescriptor target = output) {
                if (source == null || target == null) throw new IOException("The PDF could not be opened.");
                try (PdfRenderer renderer = new PdfRenderer(source);
                     ParcelFileDescriptor.AutoCloseOutputStream destination =
                             new ParcelFileDescriptor.AutoCloseOutputStream(target)) {
                    pages = renderer.getPageCount();
                    if (pages <= 0 || pages > PdfStore.MAX_PAGES) {
                        throw new IOException("Choose a PDF with 1 to " + PdfStore.MAX_PAGES + " pages.");
                    }
                    StreamingImagePdf writer = new StreamingImagePdf(destination, PdfStore.MAX_PDF_BYTES);
                    for (int index = 0; index < pages; index++) {
                        renderPage(renderer, index, profile, writer);
                        send(reply, Message.obtain(null, PROGRESS, index + 1, pages));
                    }
                    writer.finish();
                    destination.getFD().sync();
                }
            } catch (SecurityException protectedPdf) {
                error = "Password-protected PDFs cannot be reduced.";
            } catch (IOException failure) {
                error = failure.getMessage() == null ? "This PDF could not be reduced." : failure.getMessage();
            } catch (RuntimeException failure) {
                error = "This PDF could not be reduced. It may be damaged or unsupported.";
            } catch (OutOfMemoryError failure) {
                error = "This page needs more memory. Try Compact quality.";
            }
            Message result = Message.obtain(null, RESULT, error == null ? pages : -1, 0);
            if (error != null) {
                Bundle details = new Bundle();
                details.putString("error", error);
                result.setData(details);
            }
            try { reply.send(result); }
            catch (RemoteException abandoned) { handler.post(this::terminateChild); }
            // The parent acknowledges receipt with FINISH before this process exits.
        }, "PaperPocket-PDF-recompress").start();
        return true;
    }

    private static void renderPage(PdfRenderer renderer, int index, PdfConverter.Profile profile,
                                   StreamingImagePdf writer) throws IOException {
        Bitmap bitmap = null;
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            int widthPoints = page.getWidth(), heightPoints = page.getHeight();
            if (widthPoints <= 0 || heightPoints <= 0) throw new IOException("A PDF page has invalid dimensions.");
            double scale = Math.min(profile.dpi / 72.0, 4096.0 / Math.max(widthPoints, heightPoints));
            double pixels = (double) widthPoints * heightPoints * scale * scale;
            if (pixels > MAX_RENDER_PIXELS) scale *= Math.sqrt(MAX_RENDER_PIXELS / pixels);
            int width = Math.max(1, (int) Math.round(widthPoints * scale));
            int height = Math.max(1, (int) Math.round(heightPoints * scale));
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            JpegBuffer bytes = new JpegBuffer();
            OutputStream bounded = new OutputStream() {
                @Override public void write(int value) throws IOException {
                    if (bytes.size() >= MAX_JPEG_BYTES) throw new IOException("A page image is too large to reduce.");
                    bytes.write(value);
                }
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    if (length < 0 || bytes.size() > MAX_JPEG_BYTES - length) {
                        throw new IOException("A page image is too large to reduce.");
                    }
                    bytes.write(data, offset, length);
                }
            };
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, profile.jpegQuality, bounded)) {
                throw new IOException("A PDF page could not be encoded.");
            }
            bitmap.recycle();
            bitmap = null;
            // The buffer exposes a read-only stream over its storage, avoiding a second JPEG copy.
            try (InputStream image = bytes.openInput()) {
                writer.addPage(PdfGeometry.fullPage(widthPoints, heightPoints), image,
                        bytes.size(), width, height);
            }
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private void send(Messenger reply, Message message) throws IOException {
        try { reply.send(message); }
        catch (RemoteException abandoned) { throw new IOException("PDF reduction was cancelled.", abandoned); }
    }

    private static final class JpegBuffer extends ByteArrayOutputStream {
        JpegBuffer() { super(64 * 1024); }
        InputStream openInput() { return new ByteArrayInputStream(buf, 0, count); }
    }

    @Override public boolean onUnbind(Intent intent) { terminateChild(); return false; }

    private void terminateChild() {
        if (Process.myUid() != getApplicationInfo().uid) Process.killProcess(Process.myPid());
        else stopSelf();
    }
}
