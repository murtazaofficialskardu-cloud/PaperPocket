package com.paperpocket.validation;

import com.paperpocket.app.PdfGeometry;
import com.paperpocket.app.StreamingImagePdf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;

/** Portable integration checks: run through tools/validate_pdf.py, without Android. */
public final class PdfValidationHarness {
    private static int checks;

    private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected output folder and two JPEG fixtures.");
        File output = new File(args[0]);
        if (!output.isDirectory() && !output.mkdirs()) throw new IOException("Cannot create output folder.");
        File portrait = new File(args[1]);
        File landscape = new File(args[2]);
        PdfGeometry.Layout a4 = PdfGeometry.layout(16, 24, "A4", true);
        PdfGeometry.Layout letter = PdfGeometry.layout(24, 16, "LETTER", false);
        PdfGeometry.Layout auto = PdfGeometry.layout(16, 24, "AUTO", false);

        verifyGeometryAndNames(a4, letter, auto);
        verifyLifecycle(a4, portrait);
        verifyFailures(a4, portrait, output);
        verifyLargeImageStreaming(a4, portrait, output);

        try (FileOutputStream stream = new FileOutputStream(new File(output, "validation-three-pages.pdf"))) {
            StreamingImagePdf pdf = new StreamingImagePdf(stream);
            pdf.addPage(a4, portrait, 16, 24);
            pdf.addPage(letter, landscape, 24, 16);
            pdf.addPage(auto, portrait, 16, 24);
            pdf.finish();
            require(pdf.getPageCount() == 3, "Three-page document count");
        }
        try (FileOutputStream stream = new FileOutputStream(new File(output, "validation-100-pages.pdf"))) {
            StreamingImagePdf pdf = new StreamingImagePdf(stream);
            for (int i = 0; i < 100; i++) pdf.addPage(a4, portrait, 16, 24);
            pdf.finish();
            require(pdf.getPageCount() == 100, "100-page document count");
        }
        System.out.println("Java validation passed: " + checks + " checks; 3-page and 100-page PDFs written.");
    }

    private static void verifyGeometryAndNames(PdfGeometry.Layout a4, PdfGeometry.Layout letter,
                                                PdfGeometry.Layout auto) throws Exception {
        require(a4.pageWidth == 595 && a4.pageHeight == 842, "A4 portrait size");
        near(a4.top, 24, "A4 top margin");
        near(a4.imageHeight, 794, "A4 content height");
        near(a4.imageWidth, 794 * 2f / 3f, "A4 image aspect ratio");
        require(letter.pageWidth == 792 && letter.pageHeight == 612, "Letter landscape size");
        near(letter.left, 0, "Letter full width");
        near(letter.top, 42, "Letter centered content");
        near(letter.imageHeight, 528, "Letter image aspect ratio");
        require(auto.pageWidth == 561 && auto.pageHeight == 842, "Automatic page size");
        near(auto.imageWidth, 561, "Automatic image width");
        expect(IllegalArgumentException.class, () -> PdfGeometry.layout(0, 24, "A4", true));
        expect(IllegalArgumentException.class, () -> PdfGeometry.layout(16, -1, "A4", true));
        require(PdfGeometry.normalizeName(null).equals("Document.pdf"), "Default filename");
        require(PdfGeometry.normalizeName(" . .PDF.pdf ").equals("Document.pdf"), "Empty suffix filename");
        require(PdfGeometry.normalizeName("Monthly.PDF.pdf").equals("Monthly.pdf"), "Single PDF suffix");
        String safe = PdfGeometry.normalizeName("../ invoices\\2026:October?\n.pdf");
        require(!safe.startsWith(".") && !safe.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*"), "Unsafe filename characters");
        String longName = "a".repeat(99) + "\ud83d\udcc4" + "b".repeat(20);
        require(PdfGeometry.normalizeName(longName).equals("a".repeat(99) + ".pdf"), "Surrogate-safe filename truncation");
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            require(PdfGeometry.normalizeName("Invoice.PDF").equals("Invoice.pdf"), "Locale-independent suffix");
        } finally { Locale.setDefault(saved); }
    }

    private static void verifyLifecycle(PdfGeometry.Layout layout, File jpeg) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StreamingImagePdf pdf = new StreamingImagePdf(bytes);
        require(pdf.getPageCount() == 0, "Initial page count");
        expect(IOException.class, pdf::finish);
        pdf.addPage(layout, jpeg, 16, 24);
        require(pdf.getPageCount() == 1, "Page count after append");
        pdf.finish();
        byte[] complete = bytes.toByteArray();
        pdf.finish();
        require(java.util.Arrays.equals(complete, bytes.toByteArray()), "Finish is idempotent");
        expect(IOException.class, () -> pdf.addPage(layout, jpeg, 16, 24));
        require(java.util.Arrays.equals(complete, bytes.toByteArray()), "Append after finish does not write");

        ByteArrayOutputStream invalidBytes = new ByteArrayOutputStream();
        StreamingImagePdf invalid = new StreamingImagePdf(invalidBytes);
        int headerLength = invalidBytes.size();
        expect(IOException.class, () -> invalid.addPage(layout, jpeg, 0, 24));
        require(invalidBytes.size() == headerLength && invalid.getPageCount() == 0,
                "Rejected dimensions do not emit a partial page");
        invalid.addPage(layout, jpeg, 16, 24);
        invalid.finish();

        CountingSink owned = new CountingSink();
        StreamingImagePdf ownership = new StreamingImagePdf(owned);
        ownership.addPage(layout, jpeg, 16, 24);
        ownership.finish();
        require(!owned.closed, "Writer leaves caller-owned output open");
    }

    private static void verifyFailures(PdfGeometry.Layout layout, File jpeg, File folder) throws Exception {
        expect(IllegalArgumentException.class, () -> new StreamingImagePdf(null));
        expect(IllegalArgumentException.class, () -> new StreamingImagePdf(new CountingSink(), 0));
        expect(IOException.class, () -> new StreamingImagePdf(new CountingSink(), 10));

        ByteArrayOutputStream boundedBytes = new ByteArrayOutputStream();
        StreamingImagePdf bounded = new StreamingImagePdf(boundedBytes, 128);
        expect(IOException.class, () -> bounded.addPage(layout, jpeg, 16, 24));
        require(boundedBytes.size() <= 128, "Configured byte maximum is never exceeded");
        require(bounded.getPageCount() == 0, "Failed page is not counted");
        int partial = boundedBytes.size();
        expect(IOException.class, bounded::finish);
        expect(IOException.class, () -> bounded.addPage(layout, jpeg, 16, 24));
        require(boundedBytes.size() == partial, "Failed document cannot resume writing");

        FailingSink destination = new FailingSink(500);
        StreamingImagePdf failing = new StreamingImagePdf(destination);
        expect(IOException.class, () -> failing.addPage(layout, jpeg, 16, 24));
        long afterFailure = destination.count;
        expect(IOException.class, failing::finish);
        require(destination.count == afterFailure, "Destination failure cannot produce a success trailer");

        FailingSink trailerDestination = new FailingSink(Long.MAX_VALUE);
        StreamingImagePdf trailer = new StreamingImagePdf(trailerDestination);
        trailer.addPage(layout, jpeg, 16, 24);
        trailerDestination.maximum = trailerDestination.count + 20;
        expect(IOException.class, trailer::finish);
        expect(IOException.class, trailer::finish);

        File nonJpeg = new File(folder, "invalid-image.tmp");
        try {
            Files.writeString(nonJpeg.toPath(), "This is not a JPEG image.");
            StreamingImagePdf invalidImage = new StreamingImagePdf(new CountingSink());
            expect(IOException.class, () -> invalidImage.addPage(layout, nonJpeg, 16, 24));
            require(invalidImage.getPageCount() == 0, "Non-JPEG page is rejected");
            expect(IOException.class, invalidImage::finish);
        } finally { Files.deleteIfExists(nonJpeg.toPath()); }

        StreamingImagePdf cancelled = new StreamingImagePdf(new CountingSink());
        Thread.currentThread().interrupt();
        try { expect(IOException.class, () -> cancelled.addPage(layout, jpeg, 16, 24)); }
        finally { Thread.interrupted(); }
        require(cancelled.getPageCount() == 0, "Interrupted page is not counted");
        expect(IOException.class, cancelled::finish);
    }

    /** Legal JPEG COM segments create an image larger than this harness's Java heap. */
    private static void verifyLargeImageStreaming(PdfGeometry.Layout layout, File jpeg, File folder)
            throws Exception {
        File large = new File(folder, "large-streaming-image.tmp.jpg");
        try {
            byte[] comment = new byte[65533];
            try (FileInputStream source = new FileInputStream(jpeg);
                 FileOutputStream destination = new FileOutputStream(large)) {
                require(source.read() == 255 && source.read() == 216, "Real JPEG fixture marker");
                destination.write(new byte[] {(byte) 255, (byte) 216});
                for (int i = 0; i < 512; i++) {
                    destination.write(new byte[] {(byte) 255, (byte) 254, (byte) 255, (byte) 255});
                    destination.write(comment);
                }
                source.transferTo(destination);
            }
            require(large.length() > 32L * 1024 * 1024, "Streaming fixture exceeds 32 MB");
            CountingSink sink = new CountingSink();
            StreamingImagePdf pdf = new StreamingImagePdf(sink);
            pdf.addPage(layout, large, 16, 24);
            pdf.finish();
            require(pdf.getPageCount() == 1 && sink.count > large.length(),
                    "Large JPEG streams successfully under a 24 MB heap");
        } finally { Files.deleteIfExists(large.toPath()); }
    }

    private static void near(float actual, float expected, String label) {
        require(Math.abs(actual - expected) < .002f, label + ": " + actual);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable error) {
            if (!type.isInstance(error)) throw new AssertionError("Expected " + type.getSimpleName(), error);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static class CountingSink extends OutputStream {
        long count;
        boolean closed;
        @Override public void write(int value) throws IOException { count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { count += length; }
        @Override public void close() { closed = true; }
    }

    private static final class FailingSink extends CountingSink {
        long maximum;
        FailingSink(long maximum) { this.maximum = maximum; }
        @Override public void write(int value) throws IOException {
            if (count >= maximum) throw new IOException("Injected output failure");
            super.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (count > maximum - length) throw new IOException("Injected output failure");
            super.write(bytes, offset, length);
        }
    }
}
