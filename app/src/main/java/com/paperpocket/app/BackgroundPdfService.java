package com.paperpocket.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;

/** Visible, user-started local PDF processing, independent of the activity lifetime. */
public final class BackgroundPdfService extends Service {
    public static final String ACTION_START = "com.paperpocket.app.CREATE_PDF";
    public static final String ACTION_CANCEL = "com.paperpocket.app.CANCEL_PDF";
    public static final String EXTRA_JOB_ID = "job_id";
    public static final String ACTION_SHOW_LIBRARY = "com.paperpocket.app.SHOW_LIBRARY";
    private static final String CHANNEL = "pdf_creation";
    private static final int NOTIFICATION_ID = 1301;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BackgroundPdfJobs.Runner active;
    private String latestJob;
    private int latestStart;
    private boolean timedOut;
    private volatile boolean terminalNotification;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "PDF creation", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress and completion of PDFs you create");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
        Notification notification = notification("Preparing PDF", 0, 1, true, null, null);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStart = startId;
        String id = intent == null ? null : intent.getStringExtra(EXTRA_JOB_ID);
        if (ACTION_CANCEL.equals(intent == null ? null : intent.getAction())) {
            try { BackgroundPdfJobs.cancel(this, id); }
            catch (IOException ignored) { /* The in-app status remains available. */ }
            if (active == null) {
                try { showTerminal(BackgroundPdfJobs.read(this)); }
                catch (IOException ignored) { }
                stopOwnService(startId);
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent == null ? null : intent.getAction()) || id == null) {
            if (active == null) stopOwnService(startId);
            return START_NOT_STICKY;
        }
        try {
            BackgroundPdfJobs.Status status = BackgroundPdfJobs.read(this);
            if (status == null || !id.equals(status.id) || !status.active) {
                if (active == null) {
                    showTerminal(status != null && id.equals(status.id) ? status : null);
                    stopOwnService(startId);
                }
                return START_NOT_STICKY;
            }
        } catch (IOException error) {
            if (active == null) stopOwnService(startId);
            return START_NOT_STICKY;
        }
        latestJob = id;
        if (active != null) return START_NOT_STICKY;
        begin(id, startId);
        return START_NOT_STICKY;
    }

    private void begin(String id, int startId) {
        try {
            BackgroundPdfJobs.Runner claimed = BackgroundPdfJobs.claim(this, id);
            if (claimed == null) {
                showTerminal(BackgroundPdfJobs.read(this));
                stopOwnService(startId);
                return;
            }
            active = claimed;
            PowerManager.WakeLock power = getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaperPocket:PdfCreation");
            power.setReferenceCounted(false);
            power.acquire(15 * 60 * 1000L);
            wakeLock = power;
            update("Creating PDF", 0, claimed.pages.size(), true, id, null);
            Thread worker = new Thread(() -> create(claimed, startId, power), "PaperPocket-PDF-creation");
            claimed.thread = worker;
            worker.start();
        } catch (IOException | RuntimeException error) {
            if (active != null && active.id.equals(id)) {
                try { BackgroundPdfJobs.finish(this, active, null, "PDF creation could not start. Your draft is kept."); }
                catch (IOException ignored) { }
                active = null;
            }
            release(wakeLock);
            BackgroundPdfJobs.failUnclaimed(this, id, "PDF creation could not start. Your draft is kept.");
            update("PDF creation could not start. Your draft is kept.", 0, 1, false, null, null);
            stopOwnService(startId);
        }
    }

    private void create(BackgroundPdfJobs.Runner job, int startId, PowerManager.WakeLock power) {
        File temporary = null;
        PdfRecord saved = null;
        String error = "Could not create this PDF. Your draft is kept; check storage or try Compact quality.";
        try {
            BackgroundPdfJobs.check(job);
            temporary = PdfConverter.convert(this, job.pages, job.options, (done, total) -> {
                try { BackgroundPdfJobs.progress(this, job, done, total); }
                catch (IOException failure) { throw new ProgressFailure(failure); }
                update("Creating PDF", done, total, true, job.id, null);
            });
            BackgroundPdfJobs.check(job);
            // The notification's Cancel action disappears at the same durable-save barrier.
            saved = BackgroundPdfJobs.save(this, job, temporary,
                    () -> update("Saving PDF", job.pages.size(), job.pages.size(), true, null, null));
        } catch (IOException | RuntimeException failure) {
            if (job.cancelled || Thread.currentThread().isInterrupted()) error = "";
        } catch (OutOfMemoryError failure) {
            error = "Not enough memory to create this PDF. Your draft is kept; try Compact quality.";
        } finally {
            if (temporary != null) temporary.delete();
            release(power);
            // Terminal metadata must still be persisted when cancellation interrupted the worker.
            Thread.interrupted();
            try { BackgroundPdfJobs.finish(this, job, saved, error); }
            catch (IOException failure) { /* A completed PDF remains in the library even if status storage fills. */ }
            final PdfRecord result = saved;
            final boolean cancelled = job.cancelled;
            final String detail = error;
            main.post(() -> {
                if (active == job) active = null;
                if (!job.id.equals(latestJob)) {
                    begin(latestJob, latestStart);
                    return;
                }
                if (!timedOut) {
                    String title = result != null ? "PDF ready in My PDFs"
                            : cancelled ? "PDF creation cancelled. Your draft is kept." : detail;
                    update(title, 0, 1, false, null, result == null ? null : result.id);
                }
                stopOwnService(Math.max(startId, latestStart));
            });
        }
    }

    private Notification notification(String title, int done, int total, boolean ongoing,
                                      String jobId, String pdfId) {
        Intent open = new Intent(this, MainActivity.class).setAction(ACTION_SHOW_LIBRARY)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (pdfId != null) open.putExtra("pdf_id", pdfId);
        PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_pdf).setContentTitle("PaperPocket")
                .setContentText(title).setContentIntent(content).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(ongoing).setAutoCancel(!ongoing)
                .setPublicVersion(new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_pdf).setContentTitle("PaperPocket")
                        .setContentText(ongoing ? "Creating a PDF" : "PDF creation finished").build());
        if (ongoing) builder.setProgress(Math.max(1, total), Math.max(0, done), false);
        if (ongoing && jobId != null) {
            builder.addAction(new Notification.Action.Builder(null, "Cancel", cancelAction(this, jobId)).build());
        }
        return builder.build();
    }

    /** A retained action for an old notification must never be retargeted to a newer PDF. */
    static PendingIntent cancelAction(android.content.Context context, String jobId) {
        Intent cancel = new Intent(context, BackgroundPdfService.class).setAction(ACTION_CANCEL)
                .setData(Uri.parse("paperpocket://pdf-job/" + jobId))
                .putExtra(EXTRA_JOB_ID, jobId);
        return PendingIntent.getForegroundService(context, NOTIFICATION_ID + 1,
                cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void update(String title, int done, int total, boolean ongoing, String jobId, String pdfId) {
        try {
            terminalNotification = !ongoing;
            // Notification permission is optional: Android still permits the foreground task.
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                    notification(title, done, total, ongoing, jobId, pdfId));
        } catch (SecurityException ignored) { /* Denial hides the notification, never the in-app status. */ }
    }

    private void stopOwnService(int startId) {
        // A newer start intent may already be queued, even before onStartCommand sees it.
        if (stopSelfResult(startId)) {
            stopForeground(terminalNotification ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE);
        }
    }

    private void showTerminal(BackgroundPdfJobs.Status status) {
        if (status == null || status.active) return;
        String title = "completed".equals(status.state) ? "PDF ready in My PDFs"
                : "cancelled".equals(status.state) ? "PDF creation cancelled. Your draft is kept."
                : "PDF creation stopped. Your draft is kept.";
        update(title, 0, 1, false, null, status.pdfId.isEmpty() ? null : status.pdfId);
    }

    @Override public void onTimeout(int startId, int fgsType) {
        timedOut = true;
        BackgroundPdfJobs.Runner current = active;
        if (current != null) {
            try { BackgroundPdfJobs.cancel(this, current.id); }
            catch (IOException ignored) { }
            // Android requires the service to stop promptly, including if a save is finishing.
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        release(wakeLock);
        stopSelf();
    }

    @Override public void onDestroy() {
        BackgroundPdfJobs.Runner current = active;
        if (current != null && !timedOut) {
            try { BackgroundPdfJobs.cancel(this, current.id); }
            catch (IOException ignored) { }
        }
        release(wakeLock);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static void release(PowerManager.WakeLock lock) {
        if (lock != null) {
            synchronized (lock) { if (lock.isHeld()) lock.release(); }
        }
    }

    private static final class ProgressFailure extends RuntimeException {
        ProgressFailure(IOException cause) { super(cause); }
    }
}
