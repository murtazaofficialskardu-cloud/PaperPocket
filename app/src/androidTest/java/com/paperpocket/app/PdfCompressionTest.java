package com.paperpocket.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Real decoding and rendering checks for export and existing-PDF compression. */
@RunWith(AndroidJUnit4.class)
public class PdfCompressionTest {
    private static final int IMAGE_WIDTH = 2480;
    private static final int IMAGE_HEIGHT = 3508;
    private static final int RED = Color.rgb(210, 35, 35);
    private static final int GREEN = Color.rgb(30, 170, 65);
    private static final int BLUE = Color.rgb(40, 75, 210);
    private File root;
    private Context context;
    private PageItem document;

    @Before
    public void createIsolatedDocument() throws IOException {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context application = target.getApplicationContext();
        if (application == null) application = target;
        root = new File(application.getCacheDir(), "pdf-compression-" + UUID.randomUUID());
        context = new IsolatedContext(application, root);
        File image = new File(context.getFilesDir(), "document.png");
        writeDocument(image);
        document = new PageItem(image.getAbsolutePath());
    }

    @After
    public void removeIsolatedDocument() {
        if (root != null) deleteTree(root);
    }

    @Test
    public void profilesReduceSizeWhileKeepingTextContrastAndColor() throws IOException {
        List<PageItem> pages = Collections.singletonList(document);
        File legacy = PdfConverter.convert(context, pages, legacyOptions(), null);
        PdfConverter.Options highOptions = new PdfConverter.Options();
        highOptions.profile = PdfConverter.Profile.HIGH;
        File high = PdfConverter.convert(context, pages, highOptions, null);
        // The ordinary export defaults should provide Balanced's smaller output.
        File balanced = PdfConverter.convert(context, pages, new PdfConverter.Options(), null);
        PdfConverter.Options compactOptions = new PdfConverter.Options();
        compactOptions.profile = PdfConverter.Profile.COMPACT;
        File compact = PdfConverter.convert(context, pages, compactOptions, null);

        assertTrue("Compact must be smaller than Balanced", compact.length() < balanced.length());
        assertTrue("Balanced must be smaller than High", balanced.length() < high.length());
        assertTrue("High must improve on the previous JPEG98 export", high.length() < legacy.length());
        assertDocumentContent(high, 1);
        assertDocumentContent(balanced, 1);
        assertDocumentContent(compact, 1);
    }

    @Test
    public void recompressionShrinksMultipagePdfAndPreservesOriginalBytes() throws Exception {
        PageItem landscape = document.copy();
        landscape.rotation = 90;
        File original = PdfConverter.convert(context, Arrays.asList(document, landscape),
                legacyOptions(), null);
        byte[] originalHash = digest(original);
        long originalLength = original.length();
        File compressed = PdfRecompressor.recompress(context, original,
                PdfConverter.Profile.BALANCED, null);

        assertTrue(compressed.isFile());
        assertFalse(original.getCanonicalPath().equals(compressed.getCanonicalPath()));
        assertTrue("Compression must save substantial space on photographed pages",
                compressed.length() < originalLength * .70);
        assertTrue(original.isFile());
        assertEquals(originalLength, original.length());
        assertArrayEquals("Compression must preserve the source PDF", originalHash, digest(original));
        assertDocumentContent(original, 2);
        assertDocumentContent(compressed, 2);
    }

    @Test
    public void malformedPdfDoesNotBreakSubsequentValidRecompression() throws Exception {
        File malformed = new File(context.getCacheDir(), "malformed.pdf");
        try (FileOutputStream output = new FileOutputStream(malformed)) {
            output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            output.write(new byte[256]);
            output.write("\nstartxref\n0\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
        byte[] malformedHash = digest(malformed);
        try {
            PdfRecompressor.recompress(context, malformed, PdfConverter.Profile.BALANCED, null);
            fail("A malformed PDF must not produce a compressed document.");
        } catch (IOException expected) {
            // Failed parsing must not poison the native state used by the next document.
        }
        assertArrayEquals(malformedHash, digest(malformed));

        File original = PdfConverter.convert(context, Collections.singletonList(document),
                legacyOptions(), null);
        byte[] originalHash = digest(original);
        File compressed = PdfRecompressor.recompress(context, original,
                PdfConverter.Profile.COMPACT, null);
        assertTrue(compressed.isFile());
        assertTrue(compressed.length() < original.length());
        assertArrayEquals(originalHash, digest(original));
        // PdfRenderer runs in this test process too, checking the viewer remains usable.
        assertDocumentContent(compressed, 1);
    }

    private static PdfConverter.Options legacyOptions() {
        PdfConverter.Options options = new PdfConverter.Options();
        options.profile = PdfConverter.Profile.HIGH;
        options.dpi = 300;
        options.jpegQuality = 98;
        return options;
    }

    private static void assertDocumentContent(File file, int expectedPages) throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            assertEquals(expectedPages, renderer.getPageCount());
            for (int index = 0; index < expectedPages; index++) {
                boolean rotated = index == 1;
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    assertEquals(rotated ? 842 : 595, page.getWidth());
                    assertEquals(rotated ? 595 : 842, page.getHeight());
                    Bitmap rendered = Bitmap.createBitmap(page.getWidth() * 2,
                            page.getHeight() * 2, Bitmap.Config.ARGB_8888);
                    try {
                        rendered.eraseColor(Color.WHITE);
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        assertColorNear(RED, sample(rendered, 1880, 280, rotated), 28);
                        assertColorNear(GREEN, sample(rendered, 2110, 280, rotated), 28);
                        assertColorNear(BLUE, sample(rendered, 2340, 280, rotated), 28);
                        int ink = luminance(sample(rendered, 210, 1600, rotated));
                        int paper = luminance(sample(rendered, 270, 1600, rotated));
                        assertTrue("Black details must retain contrast", ink < 90);
                        assertTrue("White gaps must remain clear", paper > 230);
                        assertTrue("Ink and paper must remain distinguishable", paper - ink > 140);
                        for (int row = 0; row < 8; row++) {
                            int baseline = 650 + row * 100;
                            assertTextContrast(rendered, new Rect(180, baseline - 70,
                                    1800, baseline + 14), rotated);
                        }
                    } finally {
                        rendered.recycle();
                    }
                }
            }
        }
    }

    private static int sample(Bitmap bitmap, int x, int y, boolean rotated) {
        Point point = renderedPoint(x, y, rotated);
        return bitmap.getPixel(point.x, point.y);
    }

    private static void assertTextContrast(Bitmap bitmap, Rect source, boolean rotated) {
        Point first = renderedPoint(source.left, source.top, rotated);
        Point last = renderedPoint(source.right, source.bottom, rotated);
        int left = Math.min(first.x, last.x);
        int top = Math.min(first.y, last.y);
        int right = Math.max(first.x, last.x);
        int bottom = Math.max(first.y, last.y);
        int dark = 0;
        int white = 0;
        int count = (right - left) * (bottom - top);
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int luminance = luminance(bitmap.getPixel(x, y));
                if (luminance < 100) dark++;
                if (luminance > 230) white++;
            }
        }
        assertTrue("Text rows must contain strong dark ink", dark > count * .012);
        assertTrue("Text rows must retain clear paper around letters", white > count * .60);
    }

    private static Point renderedPoint(int sourceX, int sourceY, boolean rotated) {
        int width = rotated ? IMAGE_HEIGHT : IMAGE_WIDTH;
        int height = rotated ? IMAGE_WIDTH : IMAGE_HEIGHT;
        int x = rotated ? IMAGE_HEIGHT - sourceY : sourceX;
        int y = rotated ? sourceX : sourceY;
        PdfGeometry.Layout layout = PdfGeometry.layout(width, height, "A4", true);
        return new Point(Math.round((layout.left + layout.imageWidth * x / width) * 2),
                Math.round((layout.top + layout.imageHeight * y / height) * 2));
    }

    private static int luminance(int color) {
        return (77 * Color.red(color) + 150 * Color.green(color) + 29 * Color.blue(color)) >> 8;
    }

    private static void assertColorNear(int expected, int actual, int tolerance) {
        assertEquals(255, Color.alpha(actual));
        assertTrue("Red channel", Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance);
        assertTrue("Green channel", Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance);
        assertTrue("Blue channel", Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance);
    }

    private static void writeDocument(File file) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setTextSize(96);
            canvas.drawText("PAPERPOCKET DOCUMENT", 180, 310, paint);
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setTextSize(56);
            for (int row = 0; row < 8; row++) {
                canvas.drawText("Page 1: Invoice 12345 / sharp text", 180,
                        650 + row * 100, paint);
            }
            for (int x = 180; x < 1140; x += 120) {
                canvas.drawRect(x, 1550, x + 60, 1680, paint);
            }
            paint.setColor(RED);
            canvas.drawRect(1800, 200, 1960, 360, paint);
            paint.setColor(GREEN);
            canvas.drawRect(2030, 200, 2190, 360, paint);
            paint.setColor(BLUE);
            canvas.drawRect(2260, 200, 2420, 360, paint);
            // A textured color image makes the fixture resemble a photographed
            // document and exposes meaningful resolution/quality size differences.
            int[] pixels = new int[2120];
            for (int y = 1830; y < 3200; y++) {
                for (int x = 0; x < pixels.length; x++) {
                    int value = ((x / 4 + 1) * 0x45d9f3b) ^ ((y / 4 + 7) * 0x119de1f3);
                    value ^= value >>> 16;
                    value *= 0x45d9f3b;
                    value ^= value >>> 16;
                    int red = 32 + (value & 255) % 192;
                    int green = 32 + ((value >>> 8) & 255) % 192;
                    int blue = 32 + ((value >>> 16) & 255) % 192;
                    pixels[x] = Color.rgb(red, green, blue);
                }
                bitmap.setPixels(pixels, 0, pixels.length, 180, y, pixels.length, 1);
            }
            paint.setColor(Color.BLACK);
            paint.setTextSize(64);
            canvas.drawText("Original page 1 - color scan", 180, 3350, paint);
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                output.getFD().sync();
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return digest.digest();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists()) assertTrue("Cannot remove test file " + file, file.delete());
    }

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
