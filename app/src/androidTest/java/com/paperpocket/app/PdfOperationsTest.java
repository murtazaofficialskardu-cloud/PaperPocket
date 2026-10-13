package com.paperpocket.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises lossless image-PDF operations with Android's real writer and renderer. */
@RunWith(AndroidJUnit4.class)
public class PdfOperationsTest {
    private static final PageSpec PORTRAIT = new PageSpec(80, 120, "A4", 595, 842,
            Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW);
    private static final PageSpec LANDSCAPE = new PageSpec(120, 80, "A4", 842, 595,
            Color.GREEN, Color.BLUE, Color.YELLOW, Color.RED);
    private static final PageSpec LETTER = new PageSpec(75, 100, "LETTER", 612, 792,
            Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN);
    private static final PageSpec AUTO = new PageSpec(60, 150, "AUTO", 337, 842,
            Color.YELLOW, Color.RED, Color.GREEN, Color.BLUE);
    private File root;
    private Context context;
    private List<File> originals;

    @Before
    public void createIsolatedSources() throws IOException {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context application = target.getApplicationContext();
        if (application == null) application = target;
        root = new File(application.getCacheDir(), "pdf-operations-" + UUID.randomUUID());
        context = new IsolatedContext(application, root);
        originals = Arrays.asList(export("a4", PORTRAIT, LANDSCAPE),
                export("letter", LETTER), export("auto", AUTO));
    }

    @After
    public void removeIsolatedSources() {
        Thread.interrupted();
        if (root != null) deleteTree(root);
    }

    @Test
    public void mergePreservesInputOrderGeometryColorsAndOriginalBytes() throws Exception {
        List<byte[]> hashes = hashes(originals);
        File merged = PdfOperations.merge(context,
                Arrays.asList(originals.get(2), originals.get(0), originals.get(1)), null);

        assertTrue(merged.isFile());
        assertRendered(merged, AUTO, PORTRAIT, LANDSCAPE, LETTER);
        assertHashes(originals, hashes);
    }

    @Test
    public void extractPreservesRequestedNonconsecutiveOrderAndSourceBytes() throws Exception {
        File source = PdfOperations.merge(context, originals, null);
        byte[] originalHash = digest(source);
        File extracted = PdfOperations.extract(context, source, new int[] {3, 0, 2}, null);

        assertRendered(extracted, AUTO, PORTRAIT, LETTER);
        assertArrayEquals(originalHash, digest(source));
        assertRendered(source, PORTRAIT, LANDSCAPE, LETTER, AUTO);
    }

    @Test
    public void duplicateAndInvalidSelectionsLeaveSourceAndCacheUntouched() throws Exception {
        File source = PdfOperations.merge(context, originals, null);
        byte[] originalHash = digest(source);
        Set<String> before = cacheFiles();
        for (int[] pages : new int[][] {{0, 0}, {}, {-1}, {4}}) {
            assertRejected(() -> PdfOperations.extract(context, source, pages, null));
            assertEquals(before, cacheFiles());
            assertArrayEquals(originalHash, digest(source));
        }
    }

    @Test
    public void malformedXrefAndImageLengthAreRejectedWithoutOutputsOrSourceChanges()
            throws Exception {
        byte[] originalBytes = readBytes(originals.get(0));
        byte[] badXref = originalBytes.clone();
        String text = new String(originalBytes, StandardCharsets.ISO_8859_1);
        int offset = text.lastIndexOf("startxref\n") + "startxref\n".length();
        assertTrue(offset >= "startxref\n".length());
        assertTrue(badXref[offset] >= '0' && badXref[offset] <= '9');
        badXref[offset] = badXref[offset] == '9' ? (byte) '8' : (byte) '9';
        File xrefFile = writeBytes("bad-xref.pdf", badXref);

        byte[] badLength = originalBytes.clone();
        int image = text.indexOf("/Subtype /Image");
        assertTrue(image >= 0);
        int lengthStart = text.indexOf("/Length ", image) + "/Length ".length();
        assertTrue(lengthStart > image);
        int lastDigit = lengthStart;
        while (lastDigit + 1 < badLength.length && badLength[lastDigit + 1] >= '0'
                && badLength[lastDigit + 1] <= '9') lastDigit++;
        assertTrue(badLength[lastDigit] >= '0' && badLength[lastDigit] <= '9');
        badLength[lastDigit] = badLength[lastDigit] == '9' ? (byte) '8'
                : (byte) (badLength[lastDigit] + 1);
        File lengthFile = writeBytes("bad-image-length.pdf", badLength);
        List<File> sources = new ArrayList<>(originals);
        sources.add(xrefFile);
        sources.add(lengthFile);
        List<byte[]> hashes = hashes(sources);
        Set<String> before = cacheFiles();

        for (File malformed : Arrays.asList(xrefFile, lengthFile)) {
            assertRejected(() -> PdfOperations.merge(context,
                    Arrays.asList(originals.get(1), malformed), null));
            assertRejected(() -> PdfOperations.extract(context, malformed, new int[] {0}, null));
            assertEquals(before, cacheFiles());
            assertHashes(sources, hashes);
        }
    }

    @Test
    public void unsupportedVectorPdfIsRejectedAndPreserved() throws Exception {
        File vector = new File(context.getFilesDir(), "vector.pdf");
        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(300, 400, 1)
                    .create());
            Paint paint = new Paint();
            paint.setColor(Color.BLUE);
            page.getCanvas().drawRect(50, 50, 250, 350, paint);
            document.finishPage(page);
            try (FileOutputStream output = new FileOutputStream(vector)) {
                document.writeTo(output);
                output.getFD().sync();
            }
        } finally {
            document.close();
        }
        byte[] originalHash = digest(vector);
        Set<String> before = cacheFiles();

        assertRejected(() -> PdfOperations.merge(context,
                Arrays.asList(originals.get(0), vector), null));
        assertRejected(() -> PdfOperations.extract(context, vector, new int[] {0}, null));
        assertArrayEquals(originalHash, digest(vector));
        assertEquals(before, cacheFiles());
    }

    @Test
    public void preCancelledOperationsPreserveInterruptSourcesAndCache() throws Exception {
        File source = PdfOperations.merge(context, originals, null);
        List<File> sources = new ArrayList<>(originals);
        sources.add(source);
        List<byte[]> hashes = hashes(sources);
        Set<String> before = cacheFiles();
        for (Operation operation : Arrays.<Operation>asList(
                () -> PdfOperations.merge(context, originals, null),
                () -> PdfOperations.extract(context, source, new int[] {3, 0, 2}, null))) {
            Thread.currentThread().interrupt();
            try {
                assertRejected(operation);
                assertTrue("Cancellation must preserve the interrupt flag",
                        Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(before, cacheFiles());
            assertHashes(sources, hashes);
        }
    }

    @Test
    public void progressCancellationRemovesPartialMergeAndExtractOutputs() throws Exception {
        File source = PdfOperations.merge(context, originals, null);
        List<File> sources = new ArrayList<>(originals);
        sources.add(source);
        List<byte[]> hashes = hashes(sources);
        Set<String> before = cacheFiles();
        for (int kind = 0; kind < 2; kind++) {
            AtomicInteger callbacks = new AtomicInteger();
            PdfConverter.Progress progress = (done, total) -> {
                callbacks.incrementAndGet();
                if (done == 1) Thread.currentThread().interrupt();
            };
            Operation operation = kind == 0
                    ? () -> PdfOperations.merge(context, originals, progress)
                    : () -> PdfOperations.extract(context, source, new int[] {3, 0, 2}, progress);
            try {
                assertRejected(operation);
                assertTrue("At least one copied page must precede cancellation", callbacks.get() > 0);
                assertTrue("Cancellation must preserve the interrupt flag",
                        Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(before, cacheFiles());
            assertHashes(sources, hashes);
        }
    }

    private File export(String name, PageSpec... specs) throws IOException {
        List<PageItem> pages = new ArrayList<>();
        for (int index = 0; index < specs.length; index++) {
            File image = new File(context.getFilesDir(), name + "-" + index + ".png");
            writeImage(image, specs[index]);
            pages.add(new PageItem(image.getAbsolutePath()));
        }
        PdfConverter.Options options = new PdfConverter.Options();
        options.paper = specs[0].paper;
        return PdfConverter.convert(context, pages, options, null);
    }

    private static void assertRendered(File file, PageSpec... specs) throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            assertEquals(specs.length, renderer.getPageCount());
            for (int index = 0; index < specs.length; index++) {
                PageSpec spec = specs[index];
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    assertEquals(spec.pageWidth, page.getWidth());
                    assertEquals(spec.pageHeight, page.getHeight());
                    Bitmap rendered = Bitmap.createBitmap(page.getWidth(), page.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    try {
                        rendered.eraseColor(Color.WHITE);
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        assertEquals(Color.WHITE, rendered.getPixel(3, 3));
                        PdfGeometry.Layout layout = PdfGeometry.layout(spec.width, spec.height,
                                spec.paper, true);
                        for (int quadrant = 0; quadrant < 4; quadrant++) {
                            int x = Math.round(layout.left + layout.imageWidth
                                    * (quadrant % 2 == 0 ? .25f : .75f));
                            int y = Math.round(layout.top + layout.imageHeight
                                    * (quadrant < 2 ? .25f : .75f));
                            assertColorNear(spec.colors[quadrant], rendered.getPixel(x, y));
                        }
                    } finally {
                        rendered.recycle();
                    }
                }
            }
        }
    }

    private static void assertColorNear(int expected, int actual) {
        assertEquals(255, Color.alpha(actual));
        assertTrue("Red channel", Math.abs(Color.red(expected) - Color.red(actual)) <= 15);
        assertTrue("Green channel", Math.abs(Color.green(expected) - Color.green(actual)) <= 15);
        assertTrue("Blue channel", Math.abs(Color.blue(expected) - Color.blue(actual)) <= 15);
    }

    private static void writeImage(File file, PageSpec spec) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint();
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                paint.setColor(spec.colors[quadrant]);
                int left = quadrant % 2 == 0 ? 0 : spec.width / 2;
                int top = quadrant < 2 ? 0 : spec.height / 2;
                canvas.drawRect(left, top, quadrant % 2 == 0 ? spec.width / 2 : spec.width,
                        quadrant < 2 ? spec.height / 2 : spec.height, paint);
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                output.getFD().sync();
            }
        } finally {
            bitmap.recycle();
        }
    }

    private File writeBytes(String name, byte[] bytes) throws IOException {
        File file = new File(context.getFilesDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.getFD().sync();
        }
        return file;
    }

    private static void assertRejected(Operation operation) throws IOException {
        try {
            operation.run();
            fail("Invalid or cancelled PDF operations must fail.");
        } catch (IOException expected) {
            // Public failure contract; no malformed document is passed to PdfRenderer.
        }
    }

    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static byte[] digest(File file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(readBytes(file));
    }

    private static List<byte[]> hashes(List<File> files) throws Exception {
        List<byte[]> hashes = new ArrayList<>();
        for (File file : files) hashes.add(digest(file));
        return hashes;
    }

    private static void assertHashes(List<File> files, List<byte[]> expected) throws Exception {
        for (int index = 0; index < files.size(); index++) {
            assertTrue(files.get(index).isFile());
            assertArrayEquals("Source changed: " + files.get(index).getName(),
                    expected.get(index), digest(files.get(index)));
        }
    }

    private Set<String> cacheFiles() throws IOException {
        Set<String> files = new HashSet<>();
        collectFiles(context.getCacheDir(), files);
        return files;
    }

    private static void collectFiles(File file, Set<String> result) throws IOException {
        if (file.isFile()) {
            result.add(file.getCanonicalPath());
            return;
        }
        File[] children = file.listFiles();
        if (children == null) throw new IOException("Cannot inspect test cache.");
        for (File child : children) collectFiles(child, result);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists()) assertTrue("Cannot remove test file " + file, file.delete());
    }

    private interface Operation { File run() throws IOException; }

    private static final class PageSpec {
        final int width;
        final int height;
        final String paper;
        final int pageWidth;
        final int pageHeight;
        final int[] colors;

        PageSpec(int width, int height, String paper, int pageWidth, int pageHeight, int... colors) {
            this.width = width;
            this.height = height;
            this.paper = paper;
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            this.colors = colors;
        }
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
