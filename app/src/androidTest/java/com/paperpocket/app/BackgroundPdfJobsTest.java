package com.paperpocket.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Real foreground-service jobs, including activity teardown, cancellation and durable source isolation. */
@RunWith(AndroidJUnit4.class)
public final class BackgroundPdfJobsTest {
    @Test public void creationSurvivesActivityFinishAndOriginalDraftUnlink() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<PageItem> pages = fixture.pages(12);
            String expectedFingerprint = BackgroundPdfJobs.fingerprint(pages);
            String id = fixture.start(pages, "Background source isolation");
            assertEquals(expectedFingerprint, BackgroundPdfJobs.read(fixture.context).sourceFingerprint);
            try {
                fixture.start(pages, "Must not run concurrently");
                fail("A second job must be rejected while creation is active");
            } catch (IOException expected) { }
            // Immutable private hardlinks retain exactly the bytes selected at start.
            assertTrue(fixture.image.delete());
            fixture.finishActivity();
            BackgroundPdfJobs.Status result = fixture.awaitTerminal(id);
            assertEquals("completed", result.state);
            assertEquals(12, result.done);
            PdfRecord saved = fixture.find(result.pdfId);
            assertNotNull(saved);
            assertEquals("Background source isolation.pdf", saved.name);
            assertEquals(12, saved.pageCount);
            assertFalse("Job snapshots must be removed after success", fixture.jobFolder(id).exists());
            assertFalse("Completion is delivered once the UI acknowledges it", result.acknowledged);
            BackgroundPdfJobs.acknowledge(fixture.context, id);
            assertTrue(BackgroundPdfJobs.read(fixture.context).acknowledged);
        }
    }

    @Test public void namedIdCardJobSavesTwoPhotosAsOnePdfPage() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<String> id = new AtomicReference<>();
            fixture.main(() -> {
                PdfConverter.Options options = new PdfConverter.Options();
                options.name = "My identity card";
                options.idCardMode = true;
                id.set(BackgroundPdfJobs.start(fixture.context, fixture.pages(2), options));
            });
            BackgroundPdfJobs.Status result = fixture.awaitTerminal(id.get());
            assertEquals("completed", result.state);
            assertEquals(2, result.total);
            assertEquals(2, result.done);
            PdfRecord saved = fixture.find(result.pdfId);
            assertNotNull(saved);
            assertEquals("My identity card.pdf", saved.name);
            assertEquals(1, saved.pageCount);
        }
    }

    @Test public void sourceValidationRejectsExternalFilesAndSymlinkLeaves() throws Exception {
        try (Fixture fixture = new Fixture()) {
            File outside = new File(fixture.context.getCacheDir(), "outside-job-source-" + UUID.randomUUID() + ".png");
            File symlink = new File(fixture.image.getParentFile(), "linked-job-source-" + UUID.randomUUID() + ".png");
            byte[] original = Files.readAllBytes(fixture.image.toPath());
            try {
                Files.copy(fixture.image.toPath(), outside.toPath());
                try {
                    fixture.start(java.util.Collections.singletonList(new PageItem(outside.getAbsolutePath())), "Invalid outside source");
                    fail("A file outside private draft storage must be rejected");
                } catch (IOException expected) { }
                android.system.Os.symlink(fixture.image.getAbsolutePath(), symlink.getAbsolutePath());
                try {
                    fixture.start(java.util.Collections.singletonList(new PageItem(symlink.getAbsolutePath())), "Invalid symlink source");
                    fail("A symlink leaf must not be accepted as an immutable source image");
                } catch (IOException expected) { }
                assertFalse(BackgroundPdfJobs.isActive(fixture.context));
                assertEquals(fixture.libraryBefore, new PdfStore(fixture.context).list().size());
                assertArrayEquals(original, Files.readAllBytes(fixture.image.toPath()));
            } finally {
                Files.deleteIfExists(symlink.toPath());
                Files.deleteIfExists(outside.toPath());
            }
        }
    }

    @Test public void queuedCancellationAndInterruptedOwnerPreserveOriginals() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<PageItem> pages = fixture.pages(60);
            byte[] original = Files.readAllBytes(fixture.image.toPath());
            AtomicReference<String> queued = new AtomicReference<>();
            fixture.main(() -> {
                queued.set(BackgroundPdfJobs.start(fixture.context, pages, new PdfConverter.Options()));
                BackgroundPdfJobs.cancel(fixture.context, queued.get());
            });
            assertEquals("cancelled", fixture.awaitTerminal(queued.get()).state);
            assertArrayEquals(original, Files.readAllBytes(fixture.image.toPath()));
            assertFalse(fixture.jobFolder(queued.get()).exists());
            assertEquals(fixture.libraryBefore, new PdfStore(fixture.context).list().size());

            // Simulate the persisted token left by an earlier process, before the queued service claims it.
            AtomicReference<String> interrupted = new AtomicReference<>();
            fixture.main(() -> {
                interrupted.set(BackgroundPdfJobs.start(fixture.context, pages, new PdfConverter.Options()));
                JSONObject saved = new JSONObject(new String(Files.readAllBytes(fixture.status.toPath()), StandardCharsets.UTF_8));
                saved.put("owner", "previous-process-token");
                Files.write(fixture.status.toPath(), saved.toString().getBytes(StandardCharsets.UTF_8));
                assertEquals("interrupted", BackgroundPdfJobs.read(fixture.context).state);
            });
            assertEquals("interrupted", fixture.awaitTerminal(interrupted.get()).state);
            assertArrayEquals(original, Files.readAllBytes(fixture.image.toPath()));
            assertFalse(fixture.jobFolder(interrupted.get()).exists());
            assertEquals(fixture.libraryBefore, new PdfStore(fixture.context).list().size());
        }
    }

    @Test public void cancellationAfterRealProgressPreservesDraftAndMakesNoPdf() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] original = Files.readAllBytes(fixture.image.toPath());
            String id = fixture.start(fixture.pages(100), "Cancelled background conversion");
            fixture.await(() -> {
                BackgroundPdfJobs.Status value = BackgroundPdfJobs.read(fixture.context);
                return value != null && id.equals(value.id) && value.done >= 1 && "running".equals(value.state);
            });
            PendingIntent notificationCancel = BackgroundPdfService.cancelAction(fixture.context, id);
            notificationCancel.send();
            assertEquals("cancelled", fixture.awaitTerminal(id).state);
            assertArrayEquals(original, Files.readAllBytes(fixture.image.toPath()));
            assertFalse(fixture.jobFolder(id).exists());
            assertEquals(fixture.libraryBefore, new PdfStore(fixture.context).list().size());
        }
    }

    @Test public void deniedNotificationsAndStaleActionsDoNotBreakNewJob() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String packageName = fixture.context.getPackageName();
            // The standalone appops entry point is available on Oreo as well as current Android.
            String prior = shell("appops get " + packageName + " POST_NOTIFICATION");
            Matcher match = Pattern.compile("POST_NOTIFICATION:\\s*([a-z_]+)").matcher(prior);
            String mode = match.find() ? match.group(1) : "default";
            try {
                assertEquals("Setting the notification app-op must not produce command errors", "",
                        shell("appops set " + packageName + " POST_NOTIFICATION ignore").trim());
                String blocked = shell("appops get " + packageName + " POST_NOTIFICATION");
                assertTrue("The platform must confirm the denied app-op: " + blocked,
                        Pattern.compile("POST_NOTIFICATION:\\s*ignore\\b").matcher(blocked).find());
                if (Build.VERSION.SDK_INT >= 33) {
                    assertFalse(fixture.context.getSystemService(NotificationManager.class).areNotificationsEnabled());
                }
                // Oreo's areNotificationsEnabled() checks package importance, not this app-op.
                // The denied app-op above still exercises conversion when notification posting is blocked.
                AtomicReference<String> first = new AtomicReference<>();
                fixture.main(() -> {
                    first.set(BackgroundPdfJobs.start(fixture.context, fixture.pages(2), new PdfConverter.Options()));
                    BackgroundPdfJobs.cancel(fixture.context, first.get());
                });
                assertEquals("cancelled", fixture.awaitTerminal(first.get()).state);
                PendingIntent staleCancel = BackgroundPdfService.cancelAction(fixture.context, first.get());
                String second = fixture.start(fixture.pages(12), "Notification denied still works");
                PendingIntent currentCancel = BackgroundPdfService.cancelAction(fixture.context, second);
                assertNotEquals("Cancel actions must retain their original job identity", staleCancel, currentCancel);
                // Send a real old notification action plus old/duplicate start intents.
                staleCancel.send();
                fixture.main(() -> {
                    fixture.context.startService(new Intent(fixture.context, BackgroundPdfService.class)
                            .setAction(BackgroundPdfService.ACTION_START)
                            .putExtra(BackgroundPdfService.EXTRA_JOB_ID, first.get()));
                    fixture.context.startService(new Intent(fixture.context, BackgroundPdfService.class)
                            .setAction(BackgroundPdfService.ACTION_START)
                            .putExtra(BackgroundPdfService.EXTRA_JOB_ID, second));
                });
                BackgroundPdfJobs.Status result = fixture.awaitTerminal(second);
                assertEquals("completed", result.state);
                assertNotNull(fixture.find(result.pdfId));
                assertEquals("Stale and repeated intents must never create extra PDFs",
                        fixture.libraryBefore + 1, new PdfStore(fixture.context).list().size());
            } finally {
                assertEquals("Restoring the notification app-op must not produce command errors", "",
                        shell("appops set " + packageName + " POST_NOTIFICATION " + mode).trim());
                String restored = shell("appops get " + packageName + " POST_NOTIFICATION");
                Matcher restoredMode = Pattern.compile("POST_NOTIFICATION:\\s*([a-z_]+)").matcher(restored);
                assertEquals("The original notification app-op must be restored", mode,
                        restoredMode.find() ? restoredMode.group(1) : "default");
            }
        }
    }

    @Test public void completionHandoffStartsNextJobExactlyOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<String> first = new AtomicReference<>();
            AtomicReference<String> second = new AtomicReference<>();
            first.set(fixture.start(fixture.pages(2), "First handoff job"));
            fixture.await(() -> {
                BackgroundPdfJobs.Status value = BackgroundPdfJobs.read(fixture.context);
                return value != null && !"preparing".equals(value.state);
            });
            // Hold activity-thread completion cleanup while the worker makes terminal state durable.
            // Starting the second job in this window exercises stopSelfResult's newer-start protection.
            if (Build.VERSION.SDK_INT <= 27) {
                // Oreo's isolated PDF validator needs main-looper Binder replies to finish saving.
                fixture.awaitTerminal(first.get());
                fixture.main(() -> second.set(BackgroundPdfJobs.start(fixture.context, fixture.pages(2), new PdfConverter.Options())));
            } else fixture.main(() -> {
                fixture.await(() -> {
                    BackgroundPdfJobs.Status value = BackgroundPdfJobs.read(fixture.context);
                    return value != null && first.get().equals(value.id) && !value.active;
                });
                second.set(BackgroundPdfJobs.start(fixture.context, fixture.pages(2), new PdfConverter.Options()));
            });
            assertEquals("completed", fixture.awaitTerminal(second.get()).state);
            assertEquals(fixture.libraryBefore + 2, new PdfStore(fixture.context).list().size());
        }
    }

    private interface Checked { void run() throws Exception; }
    private interface Condition { boolean ready() throws Exception; }

    private static final class Fixture implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final File root = new File(context.getFilesDir(), "background_pdf");
        final File status = new File(root, "status.json");
        final File draft = new File(context.getFilesDir(), "draft.json");
        final byte[] oldStatus, oldDraft;
        final File image;
        final List<String> previousIds = new ArrayList<>();
        final int libraryBefore;
        Activity activity;

        Fixture() throws Exception {
            BackgroundPdfJobs.Status existing = BackgroundPdfJobs.read(context);
            assertTrue("Other conversion tests must finish their jobs", existing == null || !existing.active);
            oldStatus = status.isFile() ? Files.readAllBytes(status.toPath()) : null;
            oldDraft = draft.isFile() ? Files.readAllBytes(draft.toPath()) : null;
            Files.deleteIfExists(status.toPath());
            Files.write(draft.toPath(), "[]".getBytes(StandardCharsets.UTF_8));
            for (PdfRecord record : new PdfStore(context).list()) previousIds.add(record.id);
            libraryBefore = previousIds.size();
            File directory = new File(context.getFilesDir(), "draft-pages");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            image = new File(directory, "background-test-" + UUID.randomUUID() + ".png");
            writePage(image);
            activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(() -> {
                java.lang.reflect.Field loading = MainActivity.class.getDeclaredField("loading");
                loading.setAccessible(true);
                return !loading.getBoolean(activity);
            });
        }

        List<PageItem> pages(int count) {
            ArrayList<PageItem> result = new ArrayList<>();
            for (int index = 0; index < count; index++) result.add(new PageItem(image.getAbsolutePath()));
            return result;
        }

        String start(List<PageItem> pages, String name) throws Exception {
            AtomicReference<String> result = new AtomicReference<>();
            AtomicReference<Exception> failure = new AtomicReference<>();
            main(() -> {
                PdfConverter.Options options = new PdfConverter.Options();
                options.name = name;
                try { result.set(BackgroundPdfJobs.start(context, pages, options)); }
                catch (IOException error) { failure.set(error); }
            });
            if (failure.get() != null) throw failure.get();
            return result.get();
        }

        void main(Checked work) {
            instrumentation.runOnMainSync(() -> {
                try { work.run(); }
                catch (Exception error) { throw new AssertionError(error); }
            });
        }

        void await(Condition condition) throws Exception {
            long deadline = SystemClock.uptimeMillis() + 30_000;
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition.ready()) return;
                SystemClock.sleep(10);
            }
            fail("Background conversion did not reach the expected state");
        }

        BackgroundPdfJobs.Status awaitTerminal(String id) throws Exception {
            await(() -> {
                BackgroundPdfJobs.Status value = BackgroundPdfJobs.read(context);
                return value != null && id.equals(value.id) && !value.active;
            });
            return BackgroundPdfJobs.read(context);
        }

        File jobFolder(String id) { return new File(root, id); }

        PdfRecord find(String id) throws Exception {
            for (PdfRecord record : new PdfStore(context).list()) if (record.id.equals(id)) return record;
            return null;
        }

        void finishActivity() {
            if (activity != null) {
                Activity closing = activity;
                main(closing::finish);
                instrumentation.waitForIdleSync();
                activity = null;
            }
        }

        @Override public void close() throws Exception {
            BackgroundPdfJobs.Status value = BackgroundPdfJobs.read(context);
            if (value != null && value.active) {
                BackgroundPdfJobs.cancel(context, value.id);
                awaitTerminal(value.id);
            }
            finishActivity();
            context.stopService(new Intent(context, BackgroundPdfService.class));
            instrumentation.waitForIdleSync();
            for (PdfRecord record : new PdfStore(context).list()) {
                if (!previousIds.contains(record.id)) new PdfStore(context).delete(record);
            }
            context.getSystemService(NotificationManager.class).cancel(1301);
            File[] entries = root.listFiles();
            if (entries != null) for (File entry : entries) {
                if (entry.isDirectory()) {
                    File[] children = entry.listFiles();
                    if (children != null) for (File child : children) child.delete();
                    entry.delete();
                } else entry.delete();
            }
            if (oldStatus != null) Files.write(status.toPath(), oldStatus);
            if (oldDraft == null) Files.deleteIfExists(draft.toPath()); else Files.write(draft.toPath(), oldDraft);
            Files.deleteIfExists(image.toPath());
        }
    }

    private static void writePage(File image) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTextSize(28);
            for (int line = 0; line < 45; line++) canvas.drawText("PaperPocket background sample " + line, 40, 50 + line * 32, paint);
            try (FileOutputStream output = new FileOutputStream(image)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                output.getFD().sync();
            }
        } finally { bitmap.recycle(); }
    }

    private static String shell(String command) throws IOException {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
}
