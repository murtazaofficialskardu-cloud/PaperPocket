package com.paperpocket.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** An unsaved, private compression result. Previewing never adds a library entry. */
public final class PdfCompressionCandidate {
    private static final long MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L;
    private static final String DIRECTORY = "compression_previews";
    private static final String ID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    public final String id;
    public final File file;
    public final String originalName;
    public final int pageCount;
    public final long originalBytes, candidateBytes;
    public final PdfSharePreset preset;

    private PdfCompressionCandidate(String id, File file, String originalName,
                                    int pageCount, long originalBytes, PdfSharePreset preset)
            throws IOException {
        if (originalName == null || originalName.length() > 500 || pageCount < 1
                || pageCount > PdfStore.MAX_PAGES || originalBytes < 1
                || originalBytes > PdfStore.MAX_PDF_BYTES) {
            throw new IOException("The compression preview is unavailable.");
        }
        this.id = id;
        this.file = file;
        this.originalName = originalName;
        this.pageCount = pageCount;
        this.originalBytes = originalBytes;
        this.candidateBytes = file.length();
        this.preset = preset == null ? PdfSharePreset.CHAT : preset;
    }

    /** Must run on a worker. The isolated renderer still opens the original read-only. */
    public static PdfCompressionCandidate prepare(Context context, PdfRecord original,
                                                  PdfSharePreset preset, PdfConverter.Progress progress)
            throws IOException {
        if (original == null || original.file == null) throw new IOException("Choose a saved PDF first.");
        long originalBytes = original.file.length();
        if (originalBytes < 1 || originalBytes > PdfStore.MAX_PDF_BYTES) {
            throw new IOException("The original PDF is unavailable.");
        }
        PdfSharePreset selected = preset == null ? PdfSharePreset.CHAT : preset;
        cleanupStale(context);
        File temporary = null;
        File preview = null;
        boolean retained = false;
        try {
            temporary = PdfRecompressor.recompress(context, original.file, selected.profile, progress);
            checkCancelled();
            File directory = directory(context);
            String id = UUID.randomUUID().toString();
            preview = new File(directory, id + ".pdf");
            if (preview.exists() || !temporary.renameTo(preview)) {
                throw new IOException("Unable to prepare the compressed preview.");
            }
            temporary = null;
            PdfCompressionCandidate candidate = restore(context, id, original.name,
                    original.pageCount, originalBytes, selected);
            checkCancelled();
            retained = true;
            return candidate;
        } finally {
            if (temporary != null) temporary.delete();
            if (!retained && preview != null) preview.delete();
        }
    }

    /** Restore only the app's own complete candidate format; run this validation off the UI thread. */
    public static PdfCompressionCandidate restore(Context context, String id, String originalName,
                                                  int pages, long originalBytes, PdfSharePreset preset)
            throws IOException {
        File file = previewFile(context, id);
        try (ImagePdfReader reader = new ImagePdfReader(file, PdfStore.MAX_PAGES, PdfStore.MAX_PDF_BYTES)) {
            if (reader.pages.size() != pages) throw new IOException("The compression preview changed.");
        }
        return new PdfCompressionCandidate(id, file, originalName, pages, originalBytes, preset);
    }

    /** The private viewer receives an ID, never an arbitrary cache path. */
    public static File previewFile(Context context, String id) throws IOException {
        if (id == null || !id.matches(ID_PATTERN)) throw new IOException("Invalid compression preview.");
        File directory = directory(context).getCanonicalFile();
        String name = id + ".pdf";
        File file = new File(directory, name).getCanonicalFile();
        if (!directory.equals(file.getParentFile()) || !name.equals(file.getName())
                || !file.isFile() || file.length() < 1 || file.length() > PdfStore.MAX_PDF_BYTES) {
            throw new IOException("The compression preview is unavailable.");
        }
        return file;
    }

    public boolean isSmaller() { return candidateBytes > 0 && candidateBytes < originalBytes; }

    public double percentSaved() {
        return (originalBytes - candidateBytes) * 100.0 / originalBytes;
    }

    /** Saves exactly the reviewed bytes, only when they are strictly smaller. Run on a worker. */
    public PdfRecord save(PdfStore store) throws IOException {
        if (!isSmaller() || !file.isFile() || file.length() != candidateBytes) {
            throw new IOException("No smaller copy is available to save. Your original is kept.");
        }
        String base = originalName.replaceFirst("(?i)\\.pdf$", "");
        if (base.length() > 90) {
            int end = Character.isHighSurrogate(base.charAt(89)) ? 89 : 90;
            base = base.substring(0, end).trim();
        }
        return store.save(file, base + " - smaller", pageCount);
    }

    public void discard() { file.delete(); }

    /** Cache eviction is allowed; discarded or abandoned previews never become documents. */
    public static void cleanupStale(Context context) throws IOException {
        File[] files = directory(context).listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS;
        for (File file : files) {
            if (file.getName().matches(ID_PATTERN + "\\.pdf") && file.lastModified() < cutoff) file.delete();
        }
    }

    private static File directory(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to prepare compression preview storage.");
        }
        return directory;
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("PDF compression was cancelled.");
    }
}
