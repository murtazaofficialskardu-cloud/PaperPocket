package com.paperpocket.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Real bundled OCR, isolated native PDF parsing, and cancellation on Android. */
@RunWith(AndroidJUnit4.class)
public class OcrExtractorTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    }

    @Test public void printedImagePdfRecognizesEnglishAndPreservesSourceBytes() throws Exception {
        File source = fixture(2, true);
        try {
            byte[] before = digest(source);
            AtomicInteger done = new AtomicInteger(-1);
            String result = OcrExtractor.extract(context(), source, (completed, total) -> {
                assertEquals(2, total);
                assertEquals(done.incrementAndGet(), completed);
            });
            String text = result.toLowerCase(Locale.ROOT);
            assertTrue("Bundled Latin OCR should recognize the printed name", text.contains("paperpocket"));
            assertTrue("Printed invoice digits should be recognized", text.contains("12345"));
            assertTrue(result.contains("[Page 1 of 2]"));
            assertTrue(result.contains("[Page 2 of 2]"));
            assertEquals(2, done.get());
            assertArrayEquals("OCR must never rewrite the PDF", before, digest(source));
        } finally { source.delete(); }
    }

    @Test public void malformedPdfFailsAndNextValidPdfStillRecognizes() throws Exception {
        File broken = File.createTempFile("ocr-broken-", ".pdf", context().getCacheDir());
        File valid = fixture(1, true);
        try {
            byte[] invalid = "%PDF-1.4\ninvalid xref and page objects\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
            Files.write(broken.toPath(), invalid);
            try {
                OcrExtractor.extract(context(), broken, null);
                fail("Malformed PDF must fail instead of returning partial text");
            } catch (IOException expected) {
                assertFalse(expected instanceof InterruptedIOException);
            }
            assertArrayEquals(invalid, Files.readAllBytes(broken.toPath()));
            assertTrue(OcrExtractor.extract(context(), valid, null)
                    .toLowerCase(Locale.ROOT).contains("paperpocket"));
        } finally { broken.delete(); valid.delete(); }
    }

    @Test public void cancellationAfterNativePdfOpenAllowsCleanNextExtraction() throws Exception {
        File source = fixture(2, true);
        try {
            byte[] before = digest(source);
            AtomicInteger openedPageCount = new AtomicInteger();
            try {
                OcrExtractor.extract(context(), source, (done, total) -> {
                    if (done == 0) {
                        // The isolated renderer has already parsed this PDF and
                        // reported its count. Cancel before requesting page pixels.
                        openedPageCount.set(total);
                        Thread.currentThread().interrupt();
                    }
                });
                fail("Cancellation must not return an apparently complete result");
            } catch (InterruptedIOException cancelled) {
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(2, openedPageCount.get());
            } finally { Thread.interrupted(); }
            assertArrayEquals(before, digest(source));
            String fresh = OcrExtractor.extract(context(), source, null);
            assertTrue(fresh.toLowerCase(Locale.ROOT).contains("paperpocket"));
            assertArrayEquals(before, digest(source));
        } finally { Thread.interrupted(); source.delete(); }
    }

    @Test public void limitsFailExplicitlyAndBlankPdfReturnsNoText() throws Exception {
        File source = fixture(2, true);
        File blank = fixture(1, false);
        try {
            byte[] before = digest(source);
            OcrExtractor.Options pages = new OcrExtractor.Options();
            pages.maxPages = 1;
            try {
                OcrExtractor.extract(context(), source, pages, null);
                fail("Page limit must reject the document, not silently truncate it");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("at most 1"));
            }
            OcrExtractor.Options characters = new OcrExtractor.Options();
            characters.maxCharacters = 25;
            try {
                OcrExtractor.extract(context(), source, characters, null);
                fail("Text-size limit must be explicit");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("too long"));
            }
            assertArrayEquals(before, digest(source));
            assertEquals("", OcrExtractor.extract(context(), blank, null));
        } finally { source.delete(); blank.delete(); }
    }

    private File fixture(int count, boolean text) throws Exception {
        File source = File.createTempFile("ocr-printed-", ".pdf", context().getCacheDir());
        PdfDocument document = new PdfDocument();
        try {
            for (int index = 0; index < count; index++) {
                Bitmap image = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888);
                try {
                    image.eraseColor(Color.WHITE);
                    if (text) {
                        Canvas canvas = new Canvas(image);
                        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
                        ink.setColor(Color.BLACK);
                        ink.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                        ink.setTextSize(64);
                        canvas.drawText("PAPERPOCKET OCR", 70, 180, ink);
                        ink.setTextSize(52);
                        canvas.drawText("Invoice 12345", 70, 330, ink);
                        canvas.drawText("Total 99.50", 70, 460, ink);
                        canvas.drawText("Printed page " + (index + 1), 70, 610, ink);
                    }
                    PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(
                            500, 700, index + 1).create());
                    page.getCanvas().drawBitmap(image, null, new Rect(0, 0, 500, 700), null);
                    document.finishPage(page);
                } finally { image.recycle(); }
            }
            try (FileOutputStream output = new FileOutputStream(source)) { document.writeTo(output); }
            return source;
        } catch (Exception failure) {
            source.delete(); throw failure;
        } finally { document.close(); }
    }

    private static byte[] digest(File file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
    }
}
