package com.paperpocket.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises Android decoding, PDF rendering, and persistent storage together. */
@RunWith(AndroidJUnit4.class)
public class PdfFlowTest {
    @Test
    public void convertedPagesRenderAndLibrarySurvivesRestart() throws Exception {
        Context application = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        File root = new File(application.getCacheDir(), "pdf-flow-" + UUID.randomUUID());
        try {
            Context context = new IsolatedContext(application, root);
            File landscape = new File(context.getFilesDir(), "landscape.png");
            File portrait = new File(context.getFilesDir(), "portrait.png");
            writePage(landscape, 160, 100, new int[] {
                    Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW
            });
            writePage(portrait, 100, 160, new int[] {
                    Color.TRANSPARENT, Color.argb(128, 255, 0, 0), Color.GREEN, Color.BLUE
            });
            PageItem landscapePage = new PageItem(landscape.getAbsolutePath());
            PageItem portraitPage = new PageItem(portrait.getAbsolutePath());

            // Check the actual PNG decoder's white compositing before JPEG encoding.
            Bitmap decoded = ImageLoader.load(portraitPage, 4096);
            try {
                assertEquals(100, decoded.getWidth());
                assertEquals(160, decoded.getHeight());
                assertFalse(decoded.hasAlpha());
                assertColorNear(Color.WHITE, decoded.getPixel(25, 40), 0);
                assertColorNear(Color.rgb(255, 127, 127), decoded.getPixel(75, 40), 1);
            } finally {
                decoded.recycle();
            }

            PdfConverter.Options options = new PdfConverter.Options();
            options.paper = "A4";
            options.dpi = 300;
            options.margin = true;
            File converted = PdfConverter.convert(context,
                    Arrays.asList(landscapePage, portraitPage), options, null);
            assertTrue(converted.isFile());
            assertTrue(converted.length() > 0);
            assertRenderedPages(converted);

            PdfStore store = new PdfStore(context);
            PdfRecord saved = store.save(converted, "My scans", 2);
            assertEquals("My scans.pdf", saved.name);
            assertEquals(2, saved.pageCount);
            assertTrue(saved.file.isFile());
            assertTrue("Saving must preserve the caller's export", converted.isFile());
            String savedId = saved.id;
            File savedFile = saved.file;
            store.rename(saved, "Renamed scans.pdf");

            // Reconstructing the store reads the on-disk index, as on an app restart.
            PdfStore restarted = new PdfStore(context);
            List<PdfRecord> records = restarted.list();
            assertEquals(1, records.size());
            PdfRecord restored = records.get(0);
            assertEquals(savedId, restored.id);
            assertEquals("Renamed scans.pdf", restored.name);
            assertEquals(2, restored.pageCount);
            assertEquals(savedFile, restored.file);
            assertEquals(savedFile.length(), restored.sizeBytes);
            assertRenderedPages(restored.file);

            restarted.delete(restored);
            assertFalse(savedFile.exists());
            assertTrue(new PdfStore(context).list().isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    private static void assertRenderedPages(File pdf) throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdf,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            assertEquals(2, renderer.getPageCount());
            assertRenderedPage(renderer, 0, 842, 595, 160, 100, new int[] {
                    Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW
            });
            assertRenderedPage(renderer, 1, 595, 842, 100, 160, new int[] {
                    Color.WHITE, Color.rgb(255, 127, 127), Color.GREEN, Color.BLUE
            });
        }
    }

    private static void assertRenderedPage(PdfRenderer renderer, int index, int width,
                                           int height, int imageWidth, int imageHeight,
                                           int[] colors) {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            assertEquals(width, page.getWidth());
            assertEquals(height, page.getHeight());
            Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                rendered.eraseColor(Color.WHITE);
                page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                assertColorNear(Color.WHITE, rendered.getPixel(4, 4), 0);
                PdfGeometry.Layout layout = PdfGeometry.layout(imageWidth, imageHeight,
                        "A4", true);
                for (int quadrant = 0; quadrant < 4; quadrant++) {
                    float xFraction = quadrant % 2 == 0 ? .25f : .75f;
                    float yFraction = quadrant < 2 ? .25f : .75f;
                    int x = Math.round(layout.left + layout.imageWidth * xFraction);
                    int y = Math.round(layout.top + layout.imageHeight * yFraction);
                    // JPEG encoding can introduce small component changes.
                    assertColorNear(colors[quadrant], rendered.getPixel(x, y), 12);
                }
            } finally {
                rendered.recycle();
            }
        }
    }

    private static void assertColorNear(int expected, int actual, int tolerance) {
        assertEquals("Rendered pixels must be opaque", 255, Color.alpha(actual));
        assertTrue("Red component: expected " + Color.red(expected) + ", actual "
                + Color.red(actual), Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance);
        assertTrue("Green component: expected " + Color.green(expected) + ", actual "
                + Color.green(actual), Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance);
        assertTrue("Blue component: expected " + Color.blue(expected) + ", actual "
                + Color.blue(actual), Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance);
    }

    private static void writePage(File file, int width, int height, int[] colors)
            throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint();
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                paint.setColor(colors[quadrant]);
                int left = quadrant % 2 == 0 ? 0 : width / 2;
                int top = quadrant < 2 ? 0 : height / 2;
                canvas.drawRect(left, top, left + width / 2, top + height / 2, paint);
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                output.getFD().sync();
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (file.exists()) assertTrue("Cannot remove test file " + file, file.delete());
    }

    /** Keeps test PDFs and metadata separate from the user's real library. */
    private static final class IsolatedContext extends ContextWrapper {
        private final File files;
        private final File cache;

        IsolatedContext(Context base, File root) throws IOException {
            super(base);
            files = new File(root, "files");
            cache = new File(root, "cache");
            if (!files.mkdirs() || !cache.mkdirs()) {
                throw new IOException("Unable to create isolated test storage.");
            }
        }

        @Override public File getFilesDir() { return files; }
        @Override public File getCacheDir() { return cache; }
        @Override public Context getApplicationContext() { return this; }
    }
}
