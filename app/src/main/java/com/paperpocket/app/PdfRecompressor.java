package com.paperpocket.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Produces a new image-only PDF; the original document is always opened read-only. */
public final class PdfRecompressor {
    private static final Object LOCK = new Object();
    private static CountDownLatch previousDeath = new CountDownLatch(0);

    private PdfRecompressor() { }

    /**
     * Run on a background thread. The returned cache file belongs to the caller,
     * who can compare its size and atomically commit a smaller copy with PdfStore.save().
     * Rendering preserves pages and their physical dimensions; selectable text,
     * links, and interactive PDF content are flattened into page images.
     */
    public static File recompress(Context context, File source, PdfConverter.Profile profile,
                                  PdfConverter.Progress progress) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("PDF reduction must run in the background.");
        }
        if (Thread.currentThread().isInterrupted()) throw new IOException("PDF reduction was cancelled.");
        if (source == null || !source.isFile() || source.length() <= 0) {
            throw new IOException("Choose a readable PDF to reduce.");
        }
        if (source.length() > PdfStore.MAX_PDF_BYTES) throw new IOException("PDF files must be smaller than 512 MB.");
        Context application = context.getApplicationContext();
        Context owner = application == null ? context : application;
        File directory = new File(owner.getCacheDir(), "pdf_exports");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create the PDF export folder.");
        File partial = File.createTempFile("reduced-", ".part", directory);
        File complete = new File(directory, partial.getName().replace(".part", ".pdf"));
        try {
            synchronized (LOCK) {
                try {
                    if (!previousDeath.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("The previous PDF reduction is still finishing. Try again.");
                    }
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IOException("PDF reduction was cancelled.", cancelled);
                }
                run(owner, source, partial, profile == null ? PdfConverter.Profile.BALANCED : profile, progress);
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("PDF reduction was cancelled.");
            if (partial.length() == 0 || complete.exists() || !partial.renameTo(complete)) {
                throw new IOException("Unable to finish writing the reduced PDF.");
            }
            return complete;
        } finally {
            partial.delete();
        }
    }

    private static void run(Context context, File source, File output, PdfConverter.Profile profile,
                            PdfConverter.Progress progress) throws IOException {
        CountDownLatch result = new CountDownLatch(1), died = new CountDownLatch(1);
        previousDeath = died;
        AtomicBoolean closing = new AtomicBoolean(), bound = new AtomicBoolean(), received = new AtomicBoolean();
        AtomicInteger completed = new AtomicInteger(), total = new AtomicInteger();
        AtomicLong activity = new AtomicLong(SystemClock.elapsedRealtime());
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicReference<ServiceConnection> registration = new AtomicReference<>();
        Object transport = new Object();
        Runnable unbind = () -> {
            if (bound.compareAndSet(true, false)) {
                try { context.unbindService(registration.get()); }
                catch (IllegalArgumentException alreadyUnbound) { /* The binding is already gone. */ }
            }
        };
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == PdfRecompressService.PROGRESS) {
                completed.set(message.arg1);
                total.set(message.arg2);
                activity.set(SystemClock.elapsedRealtime());
                return true;
            }
            if (message.what != PdfRecompressService.RESULT) return false;
            if (message.arg1 <= 0) failure.set(new IOException(message.getData()
                    .getString("error", "This PDF could not be reduced.")));
            else { completed.set(message.arg1); total.set(message.arg1); }
            received.set(true);
            result.countDown();
            finish(remote.get());
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    binder.linkToDeath(() -> {
                        if (!received.get()) {
                            failure.compareAndSet(null, new IOException("The PDF reduction process stopped."));
                            result.countDown();
                        }
                        died.countDown();
                        if (closing.get()) unbind.run();
                    }, 0);
                    Messenger service = new Messenger(binder);
                    synchronized (transport) {
                        remote.set(service);
                        if (closing.get()) { finish(service); return; }
                        try (ParcelFileDescriptor input = ParcelFileDescriptor.open(source,
                                ParcelFileDescriptor.MODE_READ_ONLY);
                             ParcelFileDescriptor target = ParcelFileDescriptor.open(output,
                                     ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE)) {
                            Bundle data = new Bundle();
                            data.putParcelable("input", input);
                            data.putParcelable("output", target);
                            data.putInt("profile", profile.ordinal());
                            Message request = Message.obtain(null, PdfRecompressService.START);
                            request.setData(data);
                            request.replyTo = reply;
                            service.send(request);
                            activity.set(SystemClock.elapsedRealtime());
                        }
                    }
                } catch (IOException | RemoteException error) {
                    failure.compareAndSet(null, new IOException("The PDF could not be reduced.", error));
                    result.countDown();
                    finish(remote.get());
                    if (error instanceof RemoteException && remote.get() == null) {
                        died.countDown();
                        if (closing.get()) unbind.run();
                    }
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) { /* Linked death settles the operation. */ }
            @Override public void onNullBinding(ComponentName name) {
                failure.set(new IOException("The PDF reduction service is unavailable."));
                result.countDown();
                died.countDown();
                if (closing.get()) unbind.run();
            }
        };
        registration.set(connection);
        boolean interrupted = false;
        int reported = 0;
        long startedAt = SystemClock.elapsedRealtime();
        try {
            boolean started = context.bindService(new Intent(context, PdfRecompressService.class), connection,
                    Context.BIND_AUTO_CREATE);
            bound.set(started);
            if (!started) { died.countDown(); throw new IOException("The PDF reduction service could not start."); }
            while (!result.await(250, TimeUnit.MILLISECONDS)) {
                int done = completed.get();
                if (progress != null && done != reported) { progress.onProgress(done, total.get()); reported = done; }
                long now = SystemClock.elapsedRealtime();
                if (now - activity.get() > 60_000 || now - startedAt > 15 * 60_000L) {
                    throw new IOException("PDF reduction timed out. Try Compact quality.");
                }
            }
            if (!died.await(10, TimeUnit.SECONDS)) throw new IOException("PDF reduction did not finish safely.");
            if (failure.get() != null) throw failure.get();
            if (progress != null && completed.get() != reported) progress.onProgress(completed.get(), total.get());
        } catch (InterruptedException cancelled) {
            interrupted = true;
            throw new IOException("PDF reduction was cancelled.", cancelled);
        } catch (SecurityException unavailable) {
            died.countDown();
            throw new IOException("The PDF reduction service is unavailable.", unavailable);
        } catch (RuntimeException problem) {
            throw new IOException("This PDF could not be reduced.", problem);
        } finally {
            synchronized (transport) { closing.set(true); finish(remote.get()); }
            if (died.getCount() == 0) unbind.run();
            else new Handler(Looper.getMainLooper()).postDelayed(unbind, 60_000);
            if (remote.get() != null) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (died.getCount() != 0 && System.nanoTime() < deadline) {
                    try { died.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (InterruptedException cancelled) { interrupted = true; }
                }
            }
            if (died.getCount() == 0) unbind.run();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void finish(Messenger service) {
        if (service == null) return;
        try { service.send(Message.obtain(null, PdfRecompressService.FINISH)); }
        catch (RemoteException alreadyGone) { /* The linked death recipient handles this. */ }
    }
}
