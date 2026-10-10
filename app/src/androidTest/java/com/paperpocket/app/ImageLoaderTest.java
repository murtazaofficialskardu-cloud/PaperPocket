package com.paperpocket.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pixel-level checks using Android's real decoder and EXIF implementation. */
@RunWith(AndroidJUnit4.class)
public class ImageLoaderTest {
    private File folder;

    @Before public void setUp() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        folder = new File(context.getCacheDir(), "image-loader-test-" + UUID.randomUUID());
        if (!folder.mkdirs()) throw new IOException("Cannot create image test storage.");
    }

    @After public void tearDown() {
        Thread.interrupted();
        File[] files = folder == null ? null : folder.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        if (folder != null && folder.exists()) assertTrue(folder.delete());
    }

    @Test public void allExifOrientationsAndUserRotationsPreserveQuadrants() throws Exception {
        int red = Color.RED, green = Color.GREEN, blue = Color.BLUE, yellow = Color.YELLOW;
        int[][] expected = {
                {red, green, blue, yellow},
                {green, red, yellow, blue},
                {yellow, blue, green, red},
                {blue, yellow, red, green},
                {red, blue, green, yellow},
                {blue, red, yellow, green},
                {yellow, green, blue, red},
                {green, yellow, red, blue}
        };
        for (int orientation = 1; orientation <= 8; orientation++) {
            File jpeg = writeQuadrants("orientation-" + orientation + ".jpg", 120, 80,
                    new int[] {red, green, blue, yellow}, Bitmap.CompressFormat.JPEG);
            ExifInterface metadata = new ExifInterface(jpeg.getAbsolutePath());
            metadata.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
            metadata.saveAttributes();
            for (int rotation = 0; rotation < 360; rotation += 90) {
                verifyOriented(jpeg, orientation, rotation, expected[orientation - 1]);
            }
            if (orientation == ExifInterface.ORIENTATION_NORMAL) {
                verifyOriented(jpeg, orientation, -90, expected[0]);
                verifyOriented(jpeg, orientation, 450, expected[0]);
            }
        }
    }

    @Test public void originalCompositesTransparencyAgainstWhite() throws Exception {
        File png = writeQuadrants("transparent.png", 80, 120, new int[] {
                Color.TRANSPARENT, Color.argb(128, 255, 0, 0), Color.GREEN, Color.BLUE
        }, Bitmap.CompressFormat.PNG);
        Bitmap bitmap = ImageLoader.load(new PageItem(png.getAbsolutePath()), 1024);
        try {
            assertFalse(bitmap.hasAlpha());
            assertColorNear(Color.WHITE, bitmap.getPixel(20, 30), 0);
            assertColorNear(Color.rgb(255, 127, 127), bitmap.getPixel(60, 30), 1);
            assertColorNear(Color.GREEN, bitmap.getPixel(20, 90), 0);
            assertColorNear(Color.BLUE, bitmap.getPixel(60, 90), 0);
        } finally { bitmap.recycle(); }
    }

    @Test public void scanFiltersIncreaseContrastAndBlackWhiteIsGrayscale() throws Exception {
        File png = writeQuadrants("contrast.png", 160, 80, new int[] {
                Color.rgb(35, 35, 35), Color.rgb(190, 190, 190),
                Color.rgb(35, 35, 35), Color.rgb(190, 190, 190)
        }, Bitmap.CompressFormat.PNG);
        for (PageItem.Filter filter : new PageItem.Filter[] {
                PageItem.Filter.ENHANCED, PageItem.Filter.BLACK_WHITE}) {
            PageItem page = new PageItem(png.getAbsolutePath());
            page.filter = filter;
            Bitmap bitmap = ImageLoader.load(page, 1024);
            try {
                assertFalse(bitmap.hasAlpha());
                int dark = Color.red(bitmap.getPixel(40, 20));
                int light = Color.red(bitmap.getPixel(120, 20));
                assertTrue("Filter must improve the source's 155-level contrast", light - dark > 200);
                assertTrue("Dark ink is retained", dark < 30);
                assertTrue("Paper is brightened", light > 230);
            } finally { bitmap.recycle(); }
        }
        File colors = writeQuadrants("colors.png", 80, 120, new int[] {
                Color.RED, Color.BLUE, Color.argb(128, 255, 0, 0), Color.TRANSPARENT
        }, Bitmap.CompressFormat.PNG);
        PageItem page = new PageItem(colors.getAbsolutePath());
        page.filter = PageItem.Filter.BLACK_WHITE;
        Bitmap gray = ImageLoader.load(page, 1024);
        try {
            assertFalse(gray.hasAlpha());
            for (int y = 0; y < gray.getHeight(); y++) {
                for (int x = 0; x < gray.getWidth(); x++) {
                    int color = gray.getPixel(x, y);
                    assertEquals(255, Color.alpha(color));
                    assertEquals(Color.red(color), Color.green(color));
                    assertEquals(Color.red(color), Color.blue(color));
                }
            }
        } finally { gray.recycle(); }
    }

    @Test public void decodingBoundsSizeWithoutUpscalingSmallImages() throws Exception {
        File png = writeQuadrants("sampled.png", 1200, 800,
                new int[] {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW}, Bitmap.CompressFormat.PNG);
        PageItem page = new PageItem(png.getAbsolutePath());
        Bitmap sampled = ImageLoader.load(page, 300);
        try {
            assertEquals(300, sampled.getWidth());
            assertEquals(200, sampled.getHeight());
            assertColorNear(Color.RED, sampled.getPixel(75, 50), 0);
        } finally { sampled.recycle(); }
        page.rotation = 90;
        Bitmap rotated = ImageLoader.load(page, 300);
        try {
            assertEquals(200, rotated.getWidth());
            assertEquals(300, rotated.getHeight());
        } finally { rotated.recycle(); }
        page.rotation = 0;
        Bitmap original = ImageLoader.load(page, 4096);
        try {
            assertEquals(1200, original.getWidth());
            assertEquals(800, original.getHeight());
        } finally { original.recycle(); }
    }

    @Test public void cancellationPreservesThreadInterruptStatus() throws Exception {
        File png = writeQuadrants("cancel.png", 80, 120,
                new int[] {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW}, Bitmap.CompressFormat.PNG);
        Thread.currentThread().interrupt();
        try {
            try {
                Bitmap unexpected = ImageLoader.load(new PageItem(png.getAbsolutePath()), 1024);
                unexpected.recycle();
                fail("Interrupted image processing must fail.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("cancelled"));
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally { Thread.interrupted(); }
    }

    @Test public void invalidRotationAndCorruptImagesAreRejected() throws Exception {
        File png = writeQuadrants("rotation.png", 80, 120,
                new int[] {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW}, Bitmap.CompressFormat.PNG);
        PageItem invalid = new PageItem(png.getAbsolutePath());
        invalid.rotation = 45;
        assertCannotLoad(invalid);
        File broken = new File(folder, "broken.jpg");
        try (FileOutputStream output = new FileOutputStream(broken)) {
            output.write(new byte[] {1, 2, 3, 4, 5});
        }
        assertCannotLoad(new PageItem(broken.getAbsolutePath()));
    }

    private static void assertCannotLoad(PageItem page) throws Exception {
        try {
            Bitmap bitmap = ImageLoader.load(page, 1024);
            bitmap.recycle();
            fail("Invalid image data must not produce a bitmap.");
        } catch (IOException expected) { /* Expected public API failure. */ }
    }

    private static void verifyOriented(File jpeg, int orientation, int rotation, int[] sourceColors)
            throws Exception {
        int turns = (((rotation % 360) + 360) % 360) / 90;
        int[] colors = sourceColors.clone();
        for (int i = 0; i < turns; i++) {
            colors = new int[] {colors[2], colors[0], colors[3], colors[1]};
        }
        PageItem page = new PageItem(jpeg.getAbsolutePath());
        page.rotation = rotation;
        Bitmap bitmap = ImageLoader.load(page, 1024);
        try {
            boolean swapped = (orientation >= 5) != (turns % 2 == 1);
            assertEquals(swapped ? 80 : 120, bitmap.getWidth());
            assertEquals(swapped ? 120 : 80, bitmap.getHeight());
            assertFalse(bitmap.hasAlpha());
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                int x = bitmap.getWidth() * (quadrant % 2 == 0 ? 1 : 3) / 4;
                int y = bitmap.getHeight() * (quadrant < 2 ? 1 : 3) / 4;
                assertColorNear(colors[quadrant], bitmap.getPixel(x, y), 12);
            }
        } finally { bitmap.recycle(); }
    }

    private File writeQuadrants(String name, int width, int height, int[] colors,
                                Bitmap.CompressFormat format) throws IOException {
        File file = new File(folder, name);
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
                if (!bitmap.compress(format, 100, output)) throw new IOException("Cannot save fixture.");
            }
        } finally { bitmap.recycle(); }
        return file;
    }

    private static void assertColorNear(int expected, int actual, int tolerance) {
        assertEquals(255, Color.alpha(actual));
        assertTrue("Red channel", Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance);
        assertTrue("Green channel", Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance);
        assertTrue("Blue channel", Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance);
    }
}
