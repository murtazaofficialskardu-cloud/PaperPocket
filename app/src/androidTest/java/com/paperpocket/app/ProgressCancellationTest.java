package com.paperpocket.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Keeps the activity-owned page-extraction queue cancellable after PDF creation moved to a service. */
@RunWith(AndroidJUnit4.class)
public class ProgressCancellationTest {
    @Test public void cancellingQueuedExtractionKeepsDraftAndOriginalAndReleasesUi() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        File draft = new File(context.getFilesDir(), "draft.json");
        byte[] oldDraft = draft.isFile() ? Files.readAllBytes(draft.toPath()) : null;
        File folder = new File(context.getFilesDir(), "draft-pages");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        File image = new File(folder, "cancel-test-" + System.nanoTime() + ".png");
        Activity activity = null;
        PdfRecord original = null;
        File converted = null;
        CountDownLatch release = new CountDownLatch(1);
        try {
            writePage(image);
            byte[] imageBefore = Files.readAllBytes(image.toPath());
            JSONArray pages = new JSONArray();
            for (int index = 0; index < 1; index++) pages.put(new JSONObject()
                    .put("id", "cancel-" + index).put("path", image.getAbsolutePath())
                    .put("rotation", 0).put("filter", "ORIGINAL"));
            byte[] draftBefore = pages.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(draft.toPath(), draftBefore);
            converted = PdfConverter.convert(context, java.util.Collections.singletonList(new PageItem(image.getAbsolutePath())), new PdfConverter.Options(), null);
            original = new PdfStore(context).save(converted, "Queued cancellation source", 1);
            byte[] originalBefore = Files.readAllBytes(original.file.toPath());
            final PdfRecord selected = original;
            int libraryBefore = new PdfStore(context).list().size();
            Intent intent = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = instrumentation.startActivitySync(intent);
            final Activity tested = activity;
            await(instrumentation, () -> !flag(tested, "loading"));
            {
                ExecutorService worker = (ExecutorService) field(tested, "work");
                CountDownLatch entered = new CountDownLatch(1);
                worker.execute(() -> {
                    entered.countDown();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
                assertTrue("Worker queue must be occupied for this scenario", entered.await(5, TimeUnit.SECONDS));
            }
            // Use the real Extract pages entry point. Cancel is an actual UI click before the queued body starts.
            Method extract = MainActivity.class.getDeclaredMethod("extractPages", PdfRecord.class, int[].class, String.class);
            extract.setAccessible(true);
            instrumentation.runOnMainSync(() -> {
                try { extract.invoke(tested, selected, new int[] { 0 }, "Cancelled extraction"); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            instrumentation.runOnMainSync(() -> {
                try {
                    ProgressDialog dialog = (ProgressDialog) field(tested, "progress");
                    assertNotNull(dialog);
                    assertFalse("Page progress must be determinate", dialog.isIndeterminate());
                    assertEquals(1, dialog.getMax());
                    assertTrue("Cancel must be available before save", dialog.getButton(-2).isEnabled());
                    dialog.getButton(-2).performClick();
                } catch (Exception error) { throw new AssertionError(error); }
            });
            release.countDown();
            await(instrumentation, () -> !flag(tested, "busy"));
            assertArrayEquals("Cancelling must preserve durable draft metadata", draftBefore, Files.readAllBytes(draft.toPath()));
            assertArrayEquals("Cancelling must preserve source page bytes", imageBefore, Files.readAllBytes(image.toPath()));
            assertArrayEquals("Cancelling must preserve the original PDF", originalBefore, Files.readAllBytes(original.file.toPath()));
            assertEquals("Cancelled operation must not add a PDF", libraryBefore, new PdfStore(context).list().size());
            instrumentation.runOnMainSync(() -> {
                try {
                    assertNull("Cancelled dialog must close", field(tested, "progress"));
                    assertNull("Cancelled task must release the UI", field(tested, "activeTask"));
                } catch (Exception error) { throw new AssertionError(error); }
            });
        } finally {
            release.countDown();
            if (activity != null) {
                Activity finishing = activity;
                ExecutorService worker = (ExecutorService) field(finishing, "work");
                instrumentation.runOnMainSync(finishing::finish);
                instrumentation.waitForIdleSync();
                assertTrue("Activity workers must stop before cleanup", worker.awaitTermination(10, TimeUnit.SECONDS));
            }
            if (oldDraft == null) Files.deleteIfExists(draft.toPath()); else Files.write(draft.toPath(), oldDraft);
            if (original != null) new PdfStore(context).delete(original);
            if (converted != null) converted.delete();
            Files.deleteIfExists(image.toPath());
        }
    }

    private interface Condition { boolean ready() throws Exception; }
    private static void await(Instrumentation instrumentation, Condition condition) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> {
                try { ready.set(condition.ready()); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            if (ready.get()) return;
            SystemClock.sleep(20);
        }
        fail("Activity did not complete the expected state transition");
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static boolean flag(Object object, String name) throws Exception { return (boolean) field(object, name); }
    private static void writePage(File file) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(); paint.setColor(Color.BLACK); paint.setTextSize(24);
            for (int line = 0; line < 50; line++) canvas.drawText("PaperPocket cancellation sample line " + line, 40, 50 + line * 30, paint);
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                output.getFD().sync();
            }
        } finally { bitmap.recycle(); }
    }
}
