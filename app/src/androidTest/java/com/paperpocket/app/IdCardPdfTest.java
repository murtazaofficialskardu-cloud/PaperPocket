package com.paperpocket.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests the real front/back composition, JPEG/PDF encoding and cancellation cleanup. */
@RunWith(AndroidJUnit4.class)
public class IdCardPdfTest {
    private File root;
    private Context context;

    @Before public void createStorage() throws IOException {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        root = new File(target.getCacheDir(), "id-card-test-" + UUID.randomUUID());
        context = new IsolatedContext(target, root);
    }

    @After public void removeStorage() {
        Thread.interrupted();
        if (root != null) deleteTree(root);
    }

    @Test public void singleA4PageContainsBothSidesAndPreservesImageBytes() throws Exception {
        File frontFile = new File(context.getFilesDir(), "front.png");
        File backFile = new File(context.getFilesDir(), "back.png");
        writeCard(frontFile, 1024, 646, "FRONT SIDE 12345",
                new int[]{Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW});
        writeCard(backFile, 646, 1024, "BACK SIDE 67890",
                new int[]{Color.CYAN, Color.MAGENTA, Color.WHITE, Color.rgb(100, 100, 100)});
        byte[] frontBefore = Files.readAllBytes(frontFile.toPath());
        byte[] backBefore = Files.readAllBytes(backFile.toPath());
        PageItem front = new PageItem(frontFile.getAbsolutePath());
        PageItem back = new PageItem(backFile.getAbsolutePath());
        back.rotation = 90;
        PdfConverter.Options options = cardOptions();
        options.profile = PdfConverter.Profile.HIGH;
        options.paper = "LETTER"; // ID mode must still be A4 portrait.
        final int[] progress = {0};
        File pdf = PdfConverter.convert(context, Arrays.asList(front, back), options,
                (done, total) -> {
                    assertEquals(2, total);
                    assertEquals(progress[0] + 1, done);
                    progress[0] = done;
                });
        assertEquals(2, progress[0]);
        assertArrayEquals(frontBefore, Files.readAllBytes(frontFile.toPath()));
        assertArrayEquals(backBefore, Files.readAllBytes(backFile.toPath()));
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdf,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            assertEquals(1, renderer.getPageCount());
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                assertEquals(595, page.getWidth());
                assertEquals(842, page.getHeight());
                Bitmap rendered = render(page);
                try {
                    assertColor(Color.WHITE, pixel(rendered, 10, 10), 0);
                    assertSheetPadding(pdf, rendered);
                    assertQuadrants(rendered, IdCardLayout.fit(0, 1024, 646),
                            new int[]{Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW});
                    // A clockwise quarter-turn maps bottom-left to top-left.
                    assertQuadrants(rendered, IdCardLayout.fit(1, 1024, 646),
                            new int[]{Color.WHITE, Color.CYAN,
                                    Color.rgb(100, 100, 100), Color.MAGENTA});
                    assertTextContrast(rendered, IdCardLayout.fit(0, 1024, 646));
                } finally { rendered.recycle(); }
            }
        }
        assertNoTemporaryFiles();
    }

    @Test public void portraitImageIsLetterboxedAndBlackWhiteFilterIsApplied() throws Exception {
        File portrait = new File(context.getFilesDir(), "portrait.png");
        writeCard(portrait, 400, 800, "FILTER TEST",
                new int[]{Color.RED, Color.RED, Color.RED, Color.RED});
        byte[] before = Files.readAllBytes(portrait.toPath());
        PageItem first = new PageItem(portrait.getAbsolutePath());
        first.filter = PageItem.Filter.BLACK_WHITE;
        PageItem second = new PageItem(portrait.getAbsolutePath());
        File pdf = PdfConverter.convert(context, Arrays.asList(first, second), cardOptions(), null);
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdf,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor);
             PdfRenderer.Page page = renderer.openPage(0)) {
            Bitmap rendered = render(page);
            try {
                IdCardLayout.Placement frame = IdCardLayout.frame(0);
                IdCardLayout.Placement fitted = IdCardLayout.fit(0, 400, 800);
                assertTrue("Portrait image needs white space beside it", fitted.width < frame.width / 2);
                assertColor(Color.WHITE, pixel(rendered, frame.left + 10,
                        frame.top + frame.height / 4), 4);
                int grayscale = pixel(rendered, fitted.left + fitted.width / 4,
                        fitted.top + fitted.height / 4);
                assertTrue("Filter removes the red color",
                        Math.abs(Color.red(grayscale) - Color.green(grayscale)) <= 2
                        && Math.abs(Color.green(grayscale) - Color.blue(grayscale)) <= 2);
                assertTrue("Filtered card retains a dark tone", Color.red(grayscale) < 160);
                IdCardLayout.Placement colorBack = IdCardLayout.fit(1, 400, 800);
                assertColor(Color.RED, pixel(rendered, colorBack.left + colorBack.width / 4,
                        colorBack.top + colorBack.height / 4), 16);
            } finally { rendered.recycle(); }
        }
        assertArrayEquals(before, Files.readAllBytes(portrait.toPath()));
        assertNoTemporaryFiles();
    }

    @Test public void cancellingAfterEitherSideLeavesNoExportAndPreservesSources() throws Exception {
        File image = new File(context.getFilesDir(), "card.png");
        writeCard(image, 640, 400, "CANCEL TEST",
                new int[]{Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW});
        byte[] before = Files.readAllBytes(image.toPath());
        List<PageItem> pages = Arrays.asList(new PageItem(image.getAbsolutePath()),
                new PageItem(image.getAbsolutePath()));
        for (int cancelAt = 1; cancelAt <= 2; cancelAt++) {
            final int side = cancelAt;
            try {
                PdfConverter.convert(context, pages, cardOptions(),
                        (done, total) -> { if (done == side) Thread.currentThread().interrupt(); });
                fail("Cancelled conversion must not return an export");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("cancelled"));
            } finally { Thread.interrupted(); }
            assertArrayEquals(before, Files.readAllBytes(image.toPath()));
            assertExportDirectoryEmpty();
        }
    }

    @Test public void invalidImageCountsAndSecondImageFailureCannotPublishPartialPdf() throws Exception {
        File image = new File(context.getFilesDir(), "card.png");
        writeCard(image, 640, 400, "SAFE ORIGINAL",
                new int[]{Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW});
        PageItem source = new PageItem(image.getAbsolutePath());
        byte[] before = Files.readAllBytes(image.toPath());
        for (List<PageItem> pages : Arrays.asList(Collections.<PageItem>emptyList(),
                Collections.singletonList(source), Arrays.asList(source, source, source),
                Arrays.asList(source, null), Arrays.asList(source,
                        new PageItem(new File(context.getFilesDir(), "missing.png").getAbsolutePath())))) {
            try {
                PdfConverter.convert(context, pages, cardOptions(), null);
                fail("Incomplete ID-card input must fail");
            } catch (IOException expected) { /* No PDF may be made from one side. */ }
            assertArrayEquals(before, Files.readAllBytes(image.toPath()));
            assertExportDirectoryEmpty();
        }
    }

    private static PdfConverter.Options cardOptions() {
        PdfConverter.Options options = new PdfConverter.Options();
        options.idCardMode = true;
        return options;
    }

    private static Bitmap render(PdfRenderer.Page page) {
        Bitmap bitmap = Bitmap.createBitmap(page.getWidth() * 2, page.getHeight() * 2,
                Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return bitmap;
    }

    private static int pixel(Bitmap bitmap, float xPoints, float yPoints) {
        return bitmap.getPixel(Math.round(xPoints * 2), Math.round(yPoints * 2));
    }

    private static void assertQuadrants(Bitmap rendered, IdCardLayout.Placement fitted,
                                        int[] colors) {
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            float x = fitted.left + fitted.width * (quadrant % 2 == 0 ? .25f : .75f);
            float y = fitted.top + fitted.height * (quadrant < 2 ? .25f : .75f);
            assertColor(colors[quadrant], pixel(rendered, x, y), 20);
        }
    }

    private static void assertTextContrast(Bitmap rendered, IdCardLayout.Placement fitted) {
        Rect text = new Rect(Math.round((fitted.left + fitted.width * .08f) * 2),
                Math.round((fitted.top + fitted.height * .40f) * 2),
                Math.round((fitted.left + fitted.width * .92f) * 2),
                Math.round((fitted.top + fitted.height * .60f) * 2));
        int dark = 0;
        int white = 0;
        for (int y = text.top; y < text.bottom; y++) {
            for (int x = text.left; x < text.right; x++) {
                int color = rendered.getPixel(x, y);
                int luminance = (77 * Color.red(color) + 150 * Color.green(color)
                        + 29 * Color.blue(color)) >> 8;
                if (luminance < 100) dark++;
                if (luminance > 230) white++;
            }
        }
        int area = text.width() * text.height();
        assertTrue("Front label retains dark letter strokes", dark > area * .015);
        assertTrue("Front label retains clear white spaces", white > area * .5);
    }

    /** Check the encoded pixels independently of PDFium's JPEG color conversion. */
    private static void assertSheetPadding(File pdf, Bitmap rendered) throws IOException {
        try (ImagePdfReader reader = new ImagePdfReader(pdf,
                PdfStore.MAX_PAGES, PdfStore.MAX_PDF_BYTES);
             InputStream jpeg = reader.image(reader.pages.get(0))) {
            Bitmap sheet = BitmapFactory.decodeStream(jpeg);
            assertNotNull("The embedded A4 sheet must decode independently", sheet);
            try {
                for (int y : new int[]{409, 421, 433}) {
                    for (int x : new int[]{267, 297, 327}) {
                        int encoded = sheet.getPixel(Math.round(x * sheet.getWidth() / 595f),
                                Math.round(y * sheet.getHeight() / 842f));
                        int displayed = pixel(rendered, x, y);
                        // JPEG and older PDFium color conversion may round white by 1–2 levels.
                        assertColor(Color.WHITE, encoded, 2);
                        assertColor(Color.WHITE, displayed, 2);
                        assertColor(encoded, displayed, 2);
                        android.util.Log.i("IdCardPdfTest", "White gap at " + x + "," + y
                                + ": JPEG=" + Integer.toHexString(encoded)
                                + ", PDF=" + Integer.toHexString(displayed));
                    }
                }
            } finally { sheet.recycle(); }
        }
    }

    private static void assertColor(int expected, int actual, int tolerance) {
        assertEquals(255, Color.alpha(actual));
        String values = ": expected=" + Integer.toHexString(expected)
                + ", actual=" + Integer.toHexString(actual) + ", tolerance=" + tolerance;
        assertTrue("Red channel" + values,
                Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance);
        assertTrue("Green channel" + values,
                Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance);
        assertTrue("Blue channel" + values,
                Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance);
    }

    private static void writeCard(File file, int width, int height, String text, int[] colors)
            throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                paint.setColor(colors[quadrant]);
                int left = quadrant % 2 == 0 ? 0 : width / 2;
                int top = quadrant < 2 ? 0 : height / 2;
                canvas.drawRect(left, top, left + width / 2, top + height / 2, paint);
            }
            paint.setColor(Color.WHITE);
            canvas.drawRect(0, height * .38f, width, height * .62f, paint);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setTextSize(width * .045f);
            canvas.drawText(text, width * .08f, height * .53f, paint);
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                output.getFD().sync();
            }
        } finally { bitmap.recycle(); }
    }

    private void assertNoTemporaryFiles() {
        File[] files = new File(context.getCacheDir(), "pdf_exports").listFiles();
        if (files != null) for (File file : files) {
            assertFalse("Temporary export leaked: " + file, file.getName().endsWith(".part")
                    || file.getName().endsWith(".jpg"));
        }
    }

    private void assertExportDirectoryEmpty() {
        File[] files = new File(context.getCacheDir(), "pdf_exports").listFiles();
        assertTrue("Failed export left cached output", files == null || files.length == 0);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists()) assertTrue("Cannot remove " + file, file.delete());
    }

    private static final class IsolatedContext extends ContextWrapper {
        private final File files;
        private final File cache;

        IsolatedContext(Context base, File root) throws IOException {
            super(base);
            files = new File(root, "files");
            cache = new File(root, "cache");
            if (!files.mkdirs() || !cache.mkdirs()) throw new IOException("Test storage unavailable.");
        }

        @Override public File getFilesDir() { return files; }
        @Override public File getCacheDir() { return cache; }
        @Override public Context getApplicationContext() { return this; }
    }
}
