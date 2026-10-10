package com.paperpocket.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps Android 8/8.1's global native PDF error state out of the app process. */
final class LegacyPdfProbe {
    private static final Object LOCK = new Object();
    private static volatile CountDownLatch previousDeath = new CountDownLatch(0);
    static final class Unavailable extends IOException {
        Unavailable(String message) { super(message); }
        Unavailable(String message, Throwable cause) { super(message, cause); }
    }

    static int pageCount(Context context, File file) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new Unavailable("PDF verification must run in the background.");
        synchronized (LOCK) {
            try {
                if (!previousDeath.await(5, TimeUnit.SECONDS))
                    throw new Unavailable("The previous PDF verifier is still finishing. Try again.");
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new Unavailable("PDF verification was cancelled.", cancelled);
            }
            return probe(context.getApplicationContext(), file);
        }
    }

    private static int probe(Context context, File file) throws IOException {
        CountDownLatch result = new CountDownLatch(1), died = new CountDownLatch(1);
        previousDeath = died;
        AtomicBoolean closing = new AtomicBoolean(), bound = new AtomicBoolean();
        Object transport = new Object();
        AtomicReference<ServiceConnection> registration = new AtomicReference<>();
        Runnable unbind = () -> {
            if (bound.compareAndSet(true, false)) {
                try { context.unbindService(registration.get()); }
                catch (IllegalArgumentException alreadyUnbound) { /* Binding is gone. */ }
            }
        };
        AtomicInteger pages = new AtomicInteger(-1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicBoolean received = new AtomicBoolean();
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what != PdfProbeService.RESULT) return false;
            pages.set(message.arg1);
            if (message.arg1 <= 0) {
                String detail = message.getData().getString("error", "This file is not a readable PDF.");
                failure.set(message.arg2 == 1 ? new Unavailable(detail) : new IOException(detail));
            }
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
                            failure.compareAndSet(null, new Unavailable("The PDF verification process stopped."));
                            result.countDown();
                        }
                        died.countDown();
                        if (closing.get()) unbind.run();
                    }, 0);
                    Messenger service = new Messenger(binder);
                    synchronized (transport) {
                    remote.set(service);
                    if (closing.get()) { finish(service); return; }
                    try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file,
                            ParcelFileDescriptor.MODE_READ_ONLY)) {
                        Message request = Message.obtain(null, PdfProbeService.PROBE);
                        android.os.Bundle data = new android.os.Bundle();
                        data.putParcelable("file", descriptor);
                        request.setData(data); request.replyTo = reply;
                        service.send(request);
                    }
                    }
                } catch (IOException | RemoteException error) {
                    failure.compareAndSet(null, new Unavailable("The PDF could not be verified.", error));
                    result.countDown(); finish(remote.get());
                    if (error instanceof RemoteException && remote.get() == null) {
                        died.countDown(); if (closing.get()) unbind.run();
                    }
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                // The linked death recipient settles the request before a new probe.
            }
            @Override public void onNullBinding(ComponentName name) {
                failure.set(new Unavailable("The PDF verification service is unavailable."));
                result.countDown(); died.countDown();
                if (closing.get()) unbind.run();
            }
        };
        registration.set(connection);
        boolean interrupted = false;
        try {
            boolean started = context.bindService(new Intent(context, PdfProbeService.class), connection,
                    Context.BIND_AUTO_CREATE);
            bound.set(started);
            if (!started) { died.countDown(); throw new Unavailable("The PDF verification service could not start."); }
            if (!result.await(35, TimeUnit.SECONDS)) throw new Unavailable("PDF verification timed out.");
            if (!died.await(10, TimeUnit.SECONDS)) throw new Unavailable("PDF verification did not finish safely.");
            if (failure.get() != null) throw failure.get();
            if (pages.get() <= 0) throw new Unavailable("The PDF does not contain any pages.");
            return pages.get();
        } catch (InterruptedException cancelled) {
            interrupted = true;
            throw new Unavailable("PDF verification was cancelled.", cancelled);
        } catch (SecurityException unavailable) {
            died.countDown();
            throw new Unavailable("The PDF verification service is unavailable.", unavailable);
        } finally {
            synchronized (transport) { closing.set(true); finish(remote.get()); }
            if (died.getCount() == 0) unbind.run();
            else {
                // Retain a pending binding so its late callback can link death and
                // terminate the child. Later callers remain gated until that death.
                new Handler(Looper.getMainLooper()).postDelayed(unbind, 45_000);
            }
            // On timeout/cancellation, onUnbind also terminates the isolated child.
            // Wait for its death before another caller can reuse the component.
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
        try { service.send(Message.obtain(null, PdfProbeService.FINISH)); }
        catch (RemoteException alreadyGone) { /* The death recipient handles this. */ }
    }
}
