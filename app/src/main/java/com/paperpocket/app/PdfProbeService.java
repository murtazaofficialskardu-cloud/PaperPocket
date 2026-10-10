package com.paperpocket.app;

import android.app.Service;
import android.content.Intent;
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

/** One PDF per isolated process: Oreo PDFium retains errors after invalid input. */
public final class PdfProbeService extends Service {
    static final int PROBE = 1, RESULT = 2, FINISH = 3;
    private final Handler handler = new Handler(Looper.getMainLooper(), this::receive);
    private final Messenger endpoint = new Messenger(handler);
    private boolean accepted;

    @Override public IBinder onBind(Intent intent) {
        return Process.myUid() != getApplicationInfo().uid ? endpoint.getBinder() : null;
    }

    private boolean receive(Message message) {
        if (message.what == FINISH) { terminateChild(); return true; }
        if (message.what != PROBE || accepted || message.replyTo == null) return false;
        accepted = true;
        Bundle data = message.getData();
        data.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
        ParcelFileDescriptor descriptor = data.getParcelable("file");
        Messenger reply = message.replyTo;
        handler.postDelayed(this::terminateChild, 45_000);
        new Thread(() -> {
            int count = -1;
            String error = null;
            boolean unavailable = false;
            try (ParcelFileDescriptor input = descriptor) {
                if (input == null) throw new IOException("The PDF could not be opened.");
                try (PdfRenderer renderer = new PdfRenderer(input)) {
                    count = renderer.getPageCount();
                    if (count <= 0) throw new IOException("The PDF does not contain any pages.");
                }
            } catch (IOException | RuntimeException failure) {
                error = "This file is not a readable PDF.";
            } catch (OutOfMemoryError failure) {
                error = "There is not enough memory to open this PDF.";
                unavailable = true;
            }
            Message response = Message.obtain(null, RESULT);
            response.arg1 = error == null ? count : -1;
            response.arg2 = unavailable ? 1 : 0;
            if (error != null) { Bundle details = new Bundle(); details.putString("error", error); response.setData(details); }
            try { reply.send(response); }
            catch (RemoteException gone) { handler.post(this::terminateChild); }
            // FINISH acknowledges that the parent received the result before exit.
        }, "PaperPocket-PDF-probe").start();
        return true;
    }

    @Override public boolean onUnbind(Intent intent) { terminateChild(); return false; }

    private void terminateChild() {
        // An isolated service has a different UID. A manifest mistake must never
        // allow this compatibility helper to terminate the application's process.
        if (Process.myUid() != getApplicationInfo().uid) Process.killProcess(Process.myPid());
        else stopSelf();
    }
}
