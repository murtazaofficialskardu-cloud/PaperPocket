package com.paperpocket.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/** Runs the real bounded reader and lossless engine without an Android runtime. */
public final class PdfOperationsValidationHarness {
    private static final long LIMIT = 512L * 1024 * 1024;
    private static int checks;
    private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        File folder = new File(args[0]);
        Files.createDirectories(folder.toPath());
        File portrait = new File(args[1]), landscape = new File(args[2]);
        File first = new File(folder, "source-first.pdf"), second = new File(folder, "source-second.pdf");
        File third = new File(folder, "source-third.pdf"), maximum = new File(folder, "source-500.pdf");
        write(first, new PdfGeometry.Layout[]{PdfGeometry.layout(16, 24, "A4", true),
                PdfGeometry.layout(24, 16, "LETTER", false)}, new File[]{portrait, landscape});
        write(second, new PdfGeometry.Layout[]{PdfGeometry.layout(16, 24, "AUTO", false)}, new File[]{portrait});
        write(third, new PdfGeometry.Layout[]{PdfGeometry.fullPage(510, 765)}, new File[]{portrait});
        byte[][] hashes = {hash(first), hash(second), hash(third)};
        File cache = new File(folder, "cache");
        int[] progress = {0};
        File merged = LosslessPdfOperations.merge(cache, List.of(second, first, third), 500, LIMIT, (done, total) -> {
            require(done == ++progress[0] && total == 4, "Merge progress follows the selected page order");
        });
        Files.move(merged.toPath(), new File(folder, "merged.pdf").toPath());
        merged = new File(folder, "merged.pdf");
        require(progress[0] == 4, "Merge completes every selected page");
        verify(merged, new int[][]{{561, 842}, {595, 842}, {792, 612}, {510, 765}},
                new File[]{portrait, portrait, landscape, portrait});
        File extracted = LosslessPdfOperations.extract(cache, merged, new int[]{2, 0}, 500, LIMIT, null);
        Files.move(extracted.toPath(), new File(folder, "extracted.pdf").toPath());
        verify(new File(folder, "extracted.pdf"), new int[][]{{792, 612}, {561, 842}}, new File[]{landscape, portrait});
        require(Arrays.equals(hashes[0], hash(first)) && Arrays.equals(hashes[1], hash(second))
                && Arrays.equals(hashes[2], hash(third)), "Merge and extract preserve every original byte");

        require(Arrays.equals(PdfPageSelection.parse(" 3, 1 - 2 ", 4, 500), new int[]{2, 0, 1}), "Human selections preserve requested order");
        for (String invalid : new String[]{"", "0", "5", "2-1", "1,,2", "1,1", "1-3,2", "1-", "all", "999999999"}) {
            fail(() -> PdfPageSelection.parse(invalid, 4, 500));
        }
        final File selected = merged;
        fail(() -> LosslessPdfOperations.extract(cache, selected, new int[]{1, 1}, 500, LIMIT, null));
        fail(() -> LosslessPdfOperations.extract(cache, selected, new int[]{-1}, 500, LIMIT, null));
        fail(() -> LosslessPdfOperations.extract(cache, selected, new int[]{4}, 500, LIMIT, null));
        fail(() -> LosslessPdfOperations.merge(cache, List.of(first, second), 2, LIMIT, null));
        empty(cache);
        fail(() -> LosslessPdfOperations.merge(cache, List.of(first, second, third), 500,
                first.length() + 16, null));
        empty(cache);

        byte[] valid = Files.readAllBytes(first.toPath());
        String text = new String(valid, StandardCharsets.ISO_8859_1);
        int xrefNumber = text.lastIndexOf("startxref\n") + "startxref\n".length();
        byte[] brokenXref = valid.clone();
        brokenXref[xrefNumber] = '9';
        File bad = new File(folder, "bad.pdf");
        Files.write(bad.toPath(), brokenXref);
        fail(() -> LosslessPdfOperations.merge(cache, List.of(first, bad), 500, LIMIT, null));
        empty(cache);
        java.util.regex.Matcher length = java.util.regex.Pattern.compile("/Filter /DCTDecode /Length ([0-9]+)").matcher(text);
        require(length.find(), "Fixture contains a JPEG image length");
        byte[] brokenLength = valid.clone();
        brokenLength[length.start(1)] = brokenLength[length.start(1)] == '9' ? (byte) '8' : (byte) '9';
        Files.write(bad.toPath(), brokenLength);
        fail(() -> LosslessPdfOperations.extract(cache, bad, new int[]{0}, 500, LIMIT, null));
        empty(cache);
        Files.writeString(bad.toPath(), "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n");
        fail(() -> LosslessPdfOperations.merge(cache, List.of(bad), 500, LIMIT, null));
        empty(cache);

        try {
            Thread.currentThread().interrupt();
            fail(() -> LosslessPdfOperations.merge(cache, List.of(first), 500, LIMIT, null));
            require(Thread.currentThread().isInterrupted(), "Pre-cancellation preserves interrupt status");
        } finally { Thread.interrupted(); }
        try {
            fail(() -> LosslessPdfOperations.merge(cache, List.of(first, second), 500, LIMIT,
                    (done, total) -> Thread.currentThread().interrupt()));
            require(Thread.currentThread().isInterrupted(), "Mid-merge cancellation preserves interrupt status");
        } finally { Thread.interrupted(); }
        empty(cache);
        try {
            fail(() -> LosslessPdfOperations.extract(cache, selected, new int[]{0}, 500, LIMIT,
                    (done, total) -> Thread.currentThread().interrupt()));
            require(Thread.currentThread().isInterrupted(), "Cancellation on the last page prevents promotion");
        } finally { Thread.interrupted(); }
        empty(cache);
        require(Arrays.equals(hashes[0], hash(first)) && Arrays.equals(hashes[1], hash(second)), "Failed operations leave source bytes intact");
        PdfGeometry.Layout[] layouts = new PdfGeometry.Layout[500];
        File[] images = new File[500];
        Arrays.fill(layouts, PdfGeometry.fullPage(510, 765));
        Arrays.fill(images, portrait);
        write(maximum, layouts, images);
        try (ImagePdfReader reader = new ImagePdfReader(maximum, 500, LIMIT)) {
            require(reader.pages.size() == 500, "Bounded reader accepts the exact 500-page limit");
        }
        fail(() -> LosslessPdfOperations.merge(cache, List.of(maximum, second), 500, LIMIT, null));
        empty(cache);
        System.out.println("Lossless PDF operations validation passed: " + checks + " checks with a 24 MiB heap.");
    }

    private static void write(File file, PdfGeometry.Layout[] layouts, File[] images) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            StreamingImagePdf writer = new StreamingImagePdf(output);
            for (int index = 0; index < layouts.length; index++) {
                boolean landscape = images[index].getName().contains("landscape");
                writer.addPage(layouts[index], images[index], landscape ? 24 : 16, landscape ? 16 : 24);
            }
            writer.finish();
        }
    }

    private static void verify(File file, int[][] sizes, File[] images) throws Exception {
        try (ImagePdfReader reader = new ImagePdfReader(file, 500, LIMIT)) {
            require(reader.pages.size() == sizes.length, "Output page count");
            for (int index = 0; index < sizes.length; index++) {
                ImagePdfReader.Page page = reader.pages.get(index);
                require(page.layout.pageWidth == sizes[index][0] && page.layout.pageHeight == sizes[index][1], "Physical page dimensions and order");
                try (InputStream input = reader.image(page)) {
                    require(Arrays.equals(input.readAllBytes(), Files.readAllBytes(images[index].toPath())), "JPEG bytes copied without recompression");
                }
            }
        }
    }

    private static byte[] hash(File file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
    }

    private static void empty(File directory) {
        String[] files = directory.list();
        require(files == null || files.length == 0, "Failure leaves no partial or promoted cache exports");
    }

    private static void fail(Action action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected an IOException");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
