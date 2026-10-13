package com.paperpocket.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
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

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PDFium runs only in a disposable isolated UID; OCR itself runs in the caller. */
public final class OcrPdfRasterService extends Service {
    static final int OPEN = 1, RENDER = 2, RESULT = 3, FINISH = 4;
    private final Handler handler = new Handler(Looper.getMainLooper(), this::receive);
    private final Messenger endpoint = new Messenger(handler);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "PaperPocket-OCR-raster"));
    private final Runnable expire = this::terminateChild;
    private PdfRenderer renderer;
    private boolean accepted;
    private boolean busy;
    private int maxDimension;
    private int maxPixels;

    @Override public IBinder onBind(Intent intent) {
        return Process.myUid() != getApplicationInfo().uid ? endpoint.getBinder() : null;
    }

    private boolean receive(Message message) {
        if (message.what == FINISH) { terminateChild(); return true; }
        if (message.replyTo == null || busy) return false;
        if (message.what == OPEN && accepted) return false;
        if (message.what != OPEN && message.what != RENDER) return false;
        if (message.what == RENDER && !accepted) return false;
        if (message.what == OPEN) accepted = true;
        busy = true;
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, 120_000);
        Bundle data = message.getData();
        data.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
        int operation = message.what;
        int requestId = message.arg1;
        Messenger reply = message.replyTo;
        worker.execute(() -> {
            Bundle result = new Bundle();
            try {
                if (operation == OPEN) open(data, result);
                else render(data, result);
                result.putBoolean("success", true);
            } catch (IOException | RuntimeException failure) {
                result.putString("error", "This PDF could not be read for text extraction.");
            } catch (OutOfMemoryError failure) {
                result.putString("error", "There is not enough memory to extract this page.");
            }
            Message response = Message.obtain(null, RESULT);
            response.arg1 = requestId;
            response.setData(result);
            handler.post(() -> {
                // Clear busy before acknowledging, so an immediate next request
                // cannot race a completed page and be silently discarded.
                busy = false;
                try { reply.send(response); }
                catch (RemoteException gone) { terminateChild(); }
            });
        });
        return true;
    }

    private void open(Bundle data, Bundle result) throws IOException {
        ParcelFileDescriptor input = data.getParcelable("file");
        if (input == null) throw new IOException("Missing PDF descriptor");
        try {
            int limit = data.getInt("maxPages");
            maxDimension = data.getInt("maxDimension");
            maxPixels = data.getInt("maxPixels");
            if (limit < 1 || limit > 100 || maxDimension < 128 || maxDimension > 3000
                    || maxPixels < 16_384 || maxPixels > 4_000_000) {
                throw new IOException("Invalid rendering limits");
            }
            renderer = new PdfRenderer(input);
            input = null; // PdfRenderer owns this descriptor after construction.
            int count = renderer.getPageCount();
            if (count < 1) throw new IOException("Empty PDF");
            result.putInt("pages", count);
            if (count > limit) {
                result.putString("error", "This PDF has " + count
                        + " pages. Text extraction supports at most " + limit
                        + " pages at a time. Split it into smaller PDFs first.");
                throw new PageLimitException();
            }
        } catch (PageLimitException limit) {
            // Keep the explicit page-limit message instead of the generic parser error.
        } finally {
            if (input != null) input.close();
        }
        if (result.containsKey("error")) result.putBoolean("limitExceeded", true);
    }

    private void render(Bundle data, Bundle result) throws IOException {
        ParcelFileDescriptor output = data.getParcelable("output");
        if (output == null) throw new IOException("Missing raster descriptor");
        Bitmap bitmap = null;
        try (ParcelFileDescriptor.AutoCloseOutputStream stream =
                     new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
            if (renderer == null) throw new IOException("PDF is not open");
            int index = data.getInt("page", -1);
            if (index < 0 || index >= renderer.getPageCount()) throw new IOException("Invalid page");
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                int width = page.getWidth(), height = page.getHeight();
                if (width < 1 || height < 1) throw new IOException("Invalid page dimensions");
                double scale = Math.min(maxDimension / (double) Math.max(width, height),
                        Math.sqrt(maxPixels / ((double) width * height)));
                int rasterWidth = Math.max(1, (int) Math.floor(width * scale));
                int rasterHeight = Math.max(1, (int) Math.floor(height * scale));
                if (rasterWidth > maxDimension || rasterHeight > maxDimension
                        || (long) rasterWidth * rasterHeight > maxPixels) {
                    throw new IOException("Page is too large");
                }
                bitmap = Bitmap.createBitmap(rasterWidth, rasterHeight, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                Matrix matrix = new Matrix();
                matrix.setScale(rasterWidth / (float) width, rasterHeight / (float) height);
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("Page could not be encoded");
                }
                stream.flush();
                result.putInt("width", rasterWidth);
                result.putInt("height", rasterHeight);
            }
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    @Override public boolean onUnbind(Intent intent) { terminateChild(); return false; }

    private void terminateChild() {
        handler.removeCallbacks(expire);
        worker.shutdownNow();
        // A missing isolatedProcess manifest flag must never kill the app process.
        if (Process.myUid() != getApplicationInfo().uid) Process.killProcess(Process.myPid());
        else stopSelf();
    }

    private static final class PageLimitException extends IOException { }
}
