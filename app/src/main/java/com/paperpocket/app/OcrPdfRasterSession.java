package com.paperpocket.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Descriptor-only transport: PDF data and raster bitmaps never cross Binder. */
final class OcrPdfRasterSession implements AutoCloseable {
    private static final Semaphore SLOT = new Semaphore(1, true);
    private static volatile CountDownLatch previousRetirement = new CountDownLatch(0);
    private final Context context;
    private final OcrExtractor.Options options;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final CountDownLatch died = new CountDownLatch(1);
    private final CountDownLatch retired = new CountDownLatch(1);
    private final Object transport = new Object();
    // Guard binding registration and its completed removal with the same lock.
    // A false flag alone must not let the next caller overtake unbindService().
    private boolean bound;
    private boolean unbound;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final AtomicReference<IOException> transportError = new AtomicReference<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Messenger remote;
    private int nextRequest;
    private int pageCount;
    private final Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        Pending request = pending.get();
        if (message.what != OcrPdfRasterService.RESULT || request == null
                || message.arg1 != request.id) return false;
        request.result = message.getData();
        request.ready.countDown();
        return true;
    }));
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (transport) {
                // BIND_AUTO_CREATE can reconnect after a child dies on Oreo.
                // This document belongs to its first Binder only: replacing it
                // could terminate the next caller's freshly started worker.
                if (remote != null || (closed.get() && !bound)) return;
                try {
                    binder.linkToDeath(() -> {
                        transportError.compareAndSet(null,
                                new IOException("The text-extraction PDF worker stopped."));
                        died.countDown();
                        connected.countDown();
                        Pending request = pending.get();
                        if (request != null) request.ready.countDown();
                        completeRetirement();
                    }, 0);
                    remote = new Messenger(binder);
                } catch (RemoteException gone) {
                    transportError.set(new IOException("The text-extraction PDF worker is unavailable.", gone));
                    died.countDown();
                }
                connected.countDown();
            }
            if (closed.get()) closeTransport();
        }
        @Override public void onServiceDisconnected(ComponentName name) { }
        @Override public void onNullBinding(ComponentName name) {
            transportError.set(new IOException("The text-extraction PDF worker is unavailable."));
            connected.countDown(); died.countDown();
            if (closed.get()) closeTransport();
        }
        @Override public void onBindingDied(ComponentName name) {
            transportError.compareAndSet(null,
                    new IOException("The text-extraction PDF worker is unavailable."));
            connected.countDown();
            synchronized (transport) {
                if (remote == null) died.countDown();
            }
            if (closed.get()) closeTransport();
        }
    };

    private OcrPdfRasterSession(Context context, OcrExtractor.Options options) {
        this.context = context.getApplicationContext();
        this.options = options;
    }

    static OcrPdfRasterSession open(Context context, File document, OcrExtractor.Options options)
            throws IOException {
        OcrExtractor.checkCancelled();
        try {
            if (!SLOT.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new IOException("Another PDF text extraction is still finishing. Try again.");
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw OcrExtractor.cancelled();
        }
        OcrPdfRasterSession session = new OcrPdfRasterSession(context, options);
        try {
            await(previousRetirement, 5_000, "The previous PDF worker is still finishing. Try again.");
            previousRetirement = session.retired;
            boolean started;
            synchronized (session.transport) {
                started = session.context.bindService(new Intent(session.context,
                        OcrPdfRasterService.class), session.connection, Context.BIND_AUTO_CREATE);
                session.bound = started;
            }
            if (!started) {
                session.died.countDown();
                throw new IOException("The text-extraction PDF worker could not start.");
            }
            await(session.connected, 15_000, "The text-extraction PDF worker timed out.");
            if (session.transportError.get() != null) throw session.transportError.get();
            try (ParcelFileDescriptor source = ParcelFileDescriptor.open(document,
                    ParcelFileDescriptor.MODE_READ_ONLY)) {
                Bundle data = new Bundle();
                data.putParcelable("file", source);
                data.putInt("maxPages", options.maxPages);
                data.putInt("maxDimension", options.maxDimension);
                data.putInt("maxPixels", options.maxPixels);
                Bundle result = session.request(OcrPdfRasterService.OPEN, data);
                session.pageCount = result.getInt("pages", -1);
                if (session.pageCount < 1) throw new IOException("This PDF contains no readable pages.");
                if (session.pageCount > options.maxPages) {
                    throw new IOException("This PDF has " + session.pageCount + " pages. Text extraction supports at most "
                            + options.maxPages + " pages at a time. Split it into smaller PDFs first.");
                }
            }
            return session;
        } catch (IOException | RuntimeException failure) {
            session.close();
            throw failure;
        }
    }

    int pageCount() { return pageCount; }

    Bitmap render(int index) throws IOException {
        OcrExtractor.checkCancelled();
        File raster = File.createTempFile("ocr-raster-", ".png", context.getCacheDir());
        try {
            Bundle result;
            try (ParcelFileDescriptor output = ParcelFileDescriptor.open(raster,
                    ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE)) {
                Bundle data = new Bundle();
                data.putInt("page", index);
                data.putParcelable("output", output);
                result = request(OcrPdfRasterService.RENDER, data);
            }
            OcrExtractor.checkCancelled();
            if (raster.length() < 1 || raster.length() > 20L * 1024 * 1024) {
                throw new IOException("The rendered OCR page is unavailable or too large.");
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(raster.getAbsolutePath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1
                    || bounds.outWidth != result.getInt("width") || bounds.outHeight != result.getInt("height")
                    || bounds.outWidth > options.maxDimension || bounds.outHeight > options.maxDimension
                    || (long) bounds.outWidth * bounds.outHeight > options.maxPixels) {
                throw new IOException("The rendered OCR page has invalid dimensions.");
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(raster.getAbsolutePath(), decode);
            if (bitmap == null) throw new IOException("The OCR page could not be decoded.");
            return bitmap;
        } finally { raster.delete(); }
    }

    private Bundle request(int operation, Bundle data) throws IOException {
        OcrExtractor.checkCancelled();
        if (closed.get() || remote == null) throw new IOException("The PDF worker is closed.");
        Pending request = new Pending(++nextRequest);
        pending.set(request);
        Message message = Message.obtain(null, operation);
        message.arg1 = request.id;
        message.setData(data);
        message.replyTo = reply;
        try {
            remote.send(message);
            await(request.ready, 35_000, "PDF page rendering timed out.");
            if (transportError.get() != null) throw transportError.get();
            Bundle result = request.result;
            if (result == null) throw new IOException("The PDF worker returned no page.");
            if (!result.getBoolean("success") || result.containsKey("error")) {
                throw new IOException(result.getString("error", "This PDF could not be read for text extraction."));
            }
            return result;
        } catch (RemoteException gone) {
            throw new IOException("The text-extraction PDF worker stopped.", gone);
        } finally { pending.compareAndSet(request, null); }
    }

    private static void await(CountDownLatch latch, long milliseconds, String message) throws IOException {
        try {
            if (!latch.await(milliseconds, TimeUnit.MILLISECONDS)) throw new IOException(message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw OcrExtractor.cancelled();
        }
        OcrExtractor.checkCancelled();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        boolean interrupted = Thread.interrupted();
        closeTransport();
        if (remote != null) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (died.getCount() != 0 && System.nanoTime() < deadline) {
                try { died.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
        }
        completeRetirement();
        SLOT.release();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void closeTransport() {
        Messenger service;
        synchronized (transport) {
            service = remote;
            if (service == null && bound && died.getCount() != 0) {
                // Retain a pending registration until its first callback can
                // identify the child. No PDF descriptor has been sent yet.
                main.postDelayed(this::unbind, 45_000);
                return;
            }
        }
        // Remove BIND_AUTO_CREATE demand before asking the accepted child to
        // exit. Otherwise Oreo can restart it while the old binding survives.
        unbind();
        if (service == null) return;
        try { service.send(Message.obtain(null, OcrPdfRasterService.FINISH)); }
        catch (RemoteException alreadyGone) { died.countDown(); completeRetirement(); }
    }

    private void unbind() {
        synchronized (transport) {
            if (unbound) return;
            if (bound) {
                try { context.unbindService(connection); }
                catch (IllegalArgumentException alreadyGone) { }
            }
            bound = false;
            unbound = true;
            // A registration cancelled before receiving any Binder has never
            // opened a PDF. There is no accepted native parser to wait for.
            if (remote == null) died.countDown();
            completeRetirement();
        }
    }

    private void completeRetirement() {
        synchronized (transport) {
            if (closed.get() && unbound && died.getCount() == 0) retired.countDown();
        }
    }

    private static final class Pending {
        final int id;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile Bundle result;
        Pending(int id) { this.id = id; }
    }
}
