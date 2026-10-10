package com.paperpocket.app;

import android.content.Context;
import android.database.Cursor;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.AtomicFile;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Private, restart-safe PDF library with atomic metadata and immutable file IDs. */
public final class PdfStore {
    public static final int MAX_PAGES = 500;
    public static final long MAX_PDF_BYTES = 512L * 1024 * 1024;
    private static final int MAX_INDEX_BYTES = 4 * 1024 * 1024;
    private static final Object LOCK = new Object();

    private final Context context;
    private final File directory;
    private final AtomicFile index;
    private final Map<String, VerifiedPdf> verifiedFiles = new HashMap<>();

    public PdfStore(Context context) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        directory = new File(this.context.getFilesDir(), "pdfs");
        index = new AtomicFile(new File(directory, "index.json"));
    }

    /** Newest first. Missing or damaged metadata is rebuilt from the PDF files. */
    public List<PdfRecord> list() throws IOException {
        synchronized (LOCK) {
            ensureDirectory();
            Map<String, PdfRecord> metadata = readIndex();
            recoverInterruptedDeletes(metadata);
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("Unable to read the PDF library.");
            List<PdfRecord> records = new ArrayList<>();
            boolean changed = metadata == null;
            if (metadata == null) metadata = new HashMap<>();
            for (File file : files) {
                if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    continue;
                }
                PdfRecord record = metadata.remove(file.getName());
                VerifiedPdf verified = verifiedFiles.get(file.getName());
                if (record == null || record.pageCount <= 0 || record.sizeBytes != file.length()
                        || record.modifiedAt != file.lastModified() || verified == null
                        || !verified.matches(file, record.pageCount)) {
                    try {
                        int pages = pageCount(file);
                        boolean factsChanged = record == null || record.pageCount != pages
                                || record.sizeBytes != file.length()
                                || record.modifiedAt != file.lastModified();
                        if (record == null) {
                            String id = idForFilename(file.getName());
                            String name = id;
                            if (isUuid(id)) {
                                name = "Document " + new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US)
                                        .format(file.lastModified());
                            }
                            record = new PdfRecord(id, PdfGeometry.normalizeName(name), pages,
                                    Math.max(1, file.lastModified()), file);
                        } else {
                            // Keep the user's display name when only PDF facts need rebuilding.
                            record.pageCount = pages;
                        }
                        record.modifiedAt = file.lastModified();
                        verifiedFiles.put(file.getName(), new VerifiedPdf(file, pages));
                        changed |= factsChanged;
                    } catch (IOException ignored) {
                        // An incomplete or unreadable file is never presented as a saved PDF.
                        changed = true;
                        verifiedFiles.remove(file.getName());
                        continue;
                    }
                }
                if (record.sizeBytes != file.length()) changed = true;
                record.file = file;
                record.sizeBytes = file.length();
                records.add(record);
            }
            if (!metadata.isEmpty()) changed = true;
            Collections.sort(records, Comparator.comparingLong((PdfRecord value) -> value.createdAt)
                    .reversed().thenComparing(value -> value.name, String.CASE_INSENSITIVE_ORDER));
            // Viewing and deleting PDFs must still work when storage is full. A later
            // mutation or refresh retries repair; the PDF files remain authoritative.
            if (changed) writeIndexBestEffort(records);
            return records;
        }
    }

    /** Save copies the source; ownership and cleanup of the source stay with the caller. */
    public PdfRecord save(File source, String name, int pages) throws IOException {
        if (source == null || !source.isFile() || source.length() <= 0) {
            throw new IOException("The PDF could not be read.");
        }
        if (source.length() > MAX_PDF_BYTES) {
            throw new IOException("PDF files must be smaller than 512 MB.");
        }
        synchronized (LOCK) {
            ensureDirectory();
            List<PdfRecord> records = list();
            String id = UUID.randomUUID().toString();
            File destination = new File(directory, id + ".pdf");
            File partial = new File(directory, id + ".part");
            boolean saved = false;
            boolean promoted = false;
            try {
                try (InputStream input = new FileInputStream(source);
                     FileOutputStream output = new FileOutputStream(partial)) {
                    copyLimited(input, output);
                    output.getFD().sync();
                }
                // Validate the completed copy rather than the source, which could change
                // during an import. Incomplete copies never enter the saved-file namespace.
                int actualPages = pageCount(partial);
                if (actualPages > MAX_PAGES) {
                    throw new IOException("A document can contain up to " + MAX_PAGES + " pages.");
                }
                if (destination.exists() || !partial.renameTo(destination)) {
                    throw new IOException("Unable to save the PDF in your library.");
                }
                promoted = true;
                PdfRecord record = new PdfRecord(id, PdfGeometry.normalizeName(name), actualPages,
                        System.currentTimeMillis(), destination);
                records.add(0, record);
                writeIndex(records);
                verifiedFiles.put(destination.getName(), new VerifiedPdf(destination, actualPages));
                saved = true;
                return record;
            } finally {
                partial.delete();
                if (!saved && promoted) destination.delete();
            }
        }
    }

    /** Imports a PDF from Storage Access Framework or the document scanner. */
    public PdfRecord importPdf(Uri uri, String name) throws IOException {
        if (uri == null) throw new IOException("Choose a PDF to import.");
        File temporary = createTempPdf();
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("The selected PDF could not be opened.");
                copyLimited(input, output);
                output.getFD().sync();
            }
            return save(temporary, name == null ? displayName(uri) : name, 0);
        } catch (SecurityException error) {
            throw new IOException("Permission to read the selected PDF was lost. Choose it again.", error);
        } finally {
            temporary.delete();
        }
    }

    public File createTempPdf() throws IOException {
        File cache = new File(context.getCacheDir(), "pdf_exports");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IOException("Unable to create the PDF export folder.");
        }
        return File.createTempFile("document-", ".pdf", cache);
    }

    /** Updates the display name without changing links or replacing another file. */
    public void rename(PdfRecord record, String name) throws IOException {
        synchronized (LOCK) {
            List<PdfRecord> records = list();
            PdfRecord stored = find(records, record);
            if (stored == null) throw new IOException("This PDF is no longer in your library.");
            String normalized = PdfGeometry.normalizeName(name);
            stored.name = normalized;
            writeIndex(records);
            record.name = normalized;
        }
    }

    /** Unlinks the selected file atomically, then repairs metadata without needing free space first. */
    public void delete(PdfRecord record) throws IOException {
        synchronized (LOCK) {
            List<PdfRecord> records = list();
            PdfRecord stored = find(records, record);
            if (stored == null) return;
            if (!stored.file.delete() && stored.file.exists()) {
                throw new IOException("Unable to remove the PDF.");
            }
            records.remove(stored);
            verifiedFiles.remove(stored.file.getName());
            // If this write fails, the stale index entry is ignored on the next list().
            // The selected PDF is already removed and should not be reported as a failure.
            writeIndexBestEffort(records);
            if (stored.thumbnailFile != null) stored.thumbnailFile.delete();
        }
    }

    /** FileProvider grants are added by the receiving ACTION_SEND or ACTION_VIEW intent. */
    public Uri uri(PdfRecord record) {
        if (record == null || record.file == null) {
            throw new IllegalArgumentException("Choose a saved PDF.");
        }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".files",
                record.file, record.name);
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create the PDF library.");
        }
    }

    private Map<String, PdfRecord> readIndex() {
        try (FileInputStream input = index.openRead();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > MAX_INDEX_BYTES) return null;
                bytes.write(buffer, 0, read);
            }
            JSONArray array = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
                    .getJSONArray("documents");
            Map<String, PdfRecord> records = new HashMap<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String filename = object.getString("filename");
                if (filename.contains("/") || filename.contains("\\") ||
                        !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) return null;
                // Identity comes from the immutable file, never from possibly corrupt
                // metadata that could give two different PDFs the same ID.
                String id = idForFilename(filename);
                PdfRecord record = new PdfRecord(id,
                        PdfGeometry.normalizeName(object.optString("name", "Document")),
                        object.optInt("pages", 0), object.optLong("created", 0),
                        new File(directory, filename));
                record.sizeBytes = object.optLong("bytes", 0);
                record.modifiedAt = object.optLong("modified", 0);
                if (record.createdAt <= 0) record.createdAt = Math.max(1, record.file.lastModified());
                records.put(filename, record);
            }
            return records;
        } catch (IOException | JSONException | RuntimeException ignored) {
            return null;
        }
    }

    private void writeIndex(List<PdfRecord> records) throws IOException {
        JSONArray documents = new JSONArray();
        try {
            for (PdfRecord record : records) {
                JSONObject object = new JSONObject();
                object.put("id", record.id);
                object.put("name", record.name);
                object.put("pages", record.pageCount);
                object.put("created", record.createdAt);
                object.put("bytes", record.sizeBytes);
                object.put("modified", record.modifiedAt);
                object.put("filename", record.file.getName());
                documents.put(object);
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("documents", documents);
            byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_INDEX_BYTES) throw new IOException("The PDF library is full.");
            FileOutputStream output = null;
            try {
                output = index.startWrite();
                output.write(encoded);
                output.getFD().sync();
                index.finishWrite(output);
                // AtomicFile.finishWrite has a void API and can log a rename failure
                // instead of throwing. Confirm the new bytes actually became readable.
                verifyIndex(encoded);
            } catch (IOException error) {
                if (output != null) index.failWrite(output);
                throw error;
            }
        } catch (JSONException error) {
            throw new IOException("Unable to save the PDF library details.", error);
        }
    }

    private void verifyIndex(byte[] expected) throws IOException {
        try (FileInputStream input = index.openRead()) {
            byte[] buffer = new byte[8192];
            int position = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (position + read > expected.length) {
                    throw new IOException("The PDF library details could not be committed.");
                }
                for (int i = 0; i < read; i++) {
                    if (buffer[i] != expected[position + i]) {
                        throw new IOException("The PDF library details could not be committed.");
                    }
                }
                position += read;
            }
            if (position != expected.length) {
                throw new IOException("The PDF library details could not be committed.");
            }
        }
    }

    private void writeIndexBestEffort(List<PdfRecord> records) {
        try {
            writeIndex(records);
        } catch (IOException ignored) {
            // No PDF content is modified. Missing/stale index facts are recoverable.
        }
    }

    /** Recover tombstones left by the earlier staged-delete implementation. */
    private void recoverInterruptedDeletes(Map<String, PdfRecord> metadata) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Unable to read the PDF library.");
        for (File removed : files) {
            String name = removed.getName();
            if (!removed.isFile() || !name.endsWith(".deleted")) continue;
            String id = name.substring(0, name.length() - ".deleted".length());
            PdfRecord indexed = null;
            if (metadata != null) {
                for (PdfRecord record : metadata.values()) {
                    if (record.id.equals(id)) { indexed = record; break; }
                }
            }
            if (metadata != null && indexed == null) {
                // The index commit completed before the interruption: finish removal.
                removed.delete();
                continue;
            }
            // If the old index still contains this PDF, or cannot be read at all,
            // preserve it. Recovery must never overwrite an existing document.
            File destination = indexed != null ? indexed.file : new File(directory, id + ".pdf");
            if (destination.exists()) destination = new File(directory, UUID.randomUUID() + ".pdf");
            // Failure leaves the staged content intact for the next refresh. It must
            // not prevent the rest of the library being viewed or deleted.
            removed.renameTo(destination);
        }
    }

    private static String idForFilename(String filename) {
        String stem = filename.substring(0, filename.length() - 4);
        if (!stem.isEmpty() && stem.length() <= 128) return stem;
        return UUID.nameUUIDFromBytes(filename.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static PdfRecord find(List<PdfRecord> records, PdfRecord requested) {
        if (requested == null) return null;
        for (PdfRecord record : records) {
            if (record.id.equals(requested.id)) return record;
        }
        return null;
    }

    /** Persisted metadata is checked once per store instance, then reused while unchanged. */
    private static final class VerifiedPdf {
        private final long bytes;
        private final long modified;
        private final int pages;

        VerifiedPdf(File file, int pages) {
            bytes = file.length();
            modified = file.lastModified();
            this.pages = pages;
        }

        boolean matches(File file, int pageCount) {
            return bytes == file.length() && modified == file.lastModified() && pages == pageCount;
        }
    }

    private static int pageCount(File file) throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int pages = renderer.getPageCount();
            if (pages <= 0) throw new IOException("The PDF does not contain any pages.");
            return pages;
        } catch (SecurityException error) {
            throw new IOException("Password-protected PDFs cannot be imported.", error);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new IOException("This file is not a readable PDF.", error);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException ignored) { }
        return "Document";
    }

    private static void copyLimited(InputStream input, FileOutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Saving was cancelled.");
            written += read;
            if (written > MAX_PDF_BYTES) throw new IOException("PDF files must be smaller than 512 MB.");
            output.write(buffer, 0, read);
        }
        if (written == 0) throw new IOException("The selected PDF is empty.");
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
