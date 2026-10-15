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
import java.text.Normalizer;
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
    private final File trashDirectory;
    private final AtomicFile index;
    private final AtomicFile folderIndex;
    private final Map<String, VerifiedPdf> verifiedFiles = new HashMap<>();

    public PdfStore(Context context) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        directory = new File(this.context.getFilesDir(), "pdfs");
        trashDirectory = new File(this.context.getFilesDir(), "pdf_trash");
        index = new AtomicFile(new File(directory, "index.json"));
        folderIndex = new AtomicFile(new File(directory, "folders.json"));
    }

    /** Newest first. Missing or damaged metadata is rebuilt from the PDF files. */
    public List<PdfRecord> list() throws IOException {
        synchronized (LOCK) {
            ensureDirectory();
            Map<String, PdfRecord> metadata = readIndex();
            recoverInterruptedOperations(metadata);
            boolean changed = metadata == null;
            if (metadata == null) metadata = new HashMap<>();
            changed |= recoverTrashRestores(metadata);
            Map<String, FolderRecord> folders = readFoldersIfAvailable();
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("Unable to read the PDF library.");
            List<PdfRecord> records = new ArrayList<>();
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
                        if (ignored instanceof LegacyPdfProbe.Unavailable) throw ignored;
                        // An incomplete or unreadable file is never presented as a saved PDF.
                        changed = true;
                        verifiedFiles.remove(file.getName());
                        continue;
                    }
                }
                if (record.sizeBytes != file.length()) changed = true;
                record.file = file;
                record.sizeBytes = file.length();
                if (folders != null && record.folderId != null && !folders.containsKey(record.folderId)) {
                    record.folderId = null;
                    changed = true;
                }
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

    /** Folder names are labels only; no document bytes move when organizing the library. */
    public List<FolderRecord> listFolders() throws IOException {
        synchronized (LOCK) {
            ensureDirectory();
            List<FolderRecord> result = new ArrayList<>(readFolders().values());
            result.sort(Comparator.comparing((FolderRecord folder) -> folder.name,
                    String.CASE_INSENSITIVE_ORDER).thenComparing(folder -> folder.id));
            return result;
        }
    }

    public FolderRecord createFolder(String name) throws IOException {
        synchronized (LOCK) {
            ensureDirectory();
            Map<String, FolderRecord> folders = readFolders();
            String normalized = normalizeFolderName(name);
            requireUniqueFolderName(folders, normalized, null);
            if (folders.size() >= 1000) throw new IOException("The library can contain up to 1,000 folders.");
            FolderRecord created = new FolderRecord(UUID.randomUUID().toString(), normalized,
                    System.currentTimeMillis());
            folders.put(created.id, created);
            writeFolders(folders);
            return created;
        }
    }

    public void renameFolder(String id, String name) throws IOException {
        synchronized (LOCK) {
            requireFolderId(id);
            ensureDirectory();
            Map<String, FolderRecord> folders = readFolders();
            FolderRecord existing = folders.get(id);
            if (existing == null) throw new IOException("This folder is no longer available.");
            String normalized = normalizeFolderName(name);
            requireUniqueFolderName(folders, normalized, id);
            folders.put(id, new FolderRecord(id, normalized, existing.createdAt));
            writeFolders(folders);
        }
    }

    /** Deletes organization only. Active and retained PDFs remain byte-for-byte unchanged. */
    public void deleteFolder(String id) throws IOException {
        synchronized (LOCK) {
            requireFolderId(id);
            ensureDirectory();
            Map<String, FolderRecord> folders = readFolders();
            if (folders.remove(id) == null) throw new IOException("This folder is no longer available.");
            // This atomic registry update is the complete commit. Membership in a
            // missing folder resolves to root, including Trash and interrupted
            // restore journals, so a process death cannot leave a partial deletion.
            // Folder UUIDs are never reused. The next list() repairs stale index IDs.
            writeFolders(folders);
        }
    }

    public void moveToFolder(PdfRecord record, String folderId) throws IOException {
        synchronized (LOCK) {
            ensureDirectory();
            if (folderId != null) {
                requireFolderId(folderId);
                if (!readFolders().containsKey(folderId)) {
                    throw new IOException("This folder is no longer available. Choose another folder.");
                }
            }
            List<PdfRecord> records = list();
            PdfRecord stored = find(records, record);
            if (stored == null) throw new IOException("This PDF is no longer in your library.");
            stored.folderId = folderId;
            writeIndex(records);
            record.folderId = folderId;
        }
    }

    public void setFavorite(PdfRecord record, boolean favorite) throws IOException {
        synchronized (LOCK) {
            List<PdfRecord> records = list();
            PdfRecord stored = find(records, record);
            if (stored == null) throw new IOException("This PDF is no longer in your library.");
            stored.favorite = favorite;
            writeIndex(records);
            record.favorite = favorite;
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

    /** Retains the original PDF bytes in private trash. Nothing is purged automatically. */
    public TrashRecord moveToTrash(PdfRecord record) throws IOException {
        synchronized (LOCK) {
            List<PdfRecord> records = list();
            PdfRecord stored = find(records, record);
            if (stored == null) throw new IOException("This PDF is no longer in your library.");
            ensureTrashDirectory();
            File source = privateFile(directory, stored.file.getName());
            TrashRecord trashed = new TrashRecord();
            trashed.id = UUID.randomUUID().toString();
            trashed.originalFilename = source.getName();
            trashed.originalId = idForFilename(source.getName());
            trashed.name = stored.name;
            trashed.pageCount = stored.pageCount;
            trashed.createdAt = stored.createdAt;
            trashed.deletedAt = System.currentTimeMillis();
            trashed.sizeBytes = source.length();
            trashed.modifiedAt = source.lastModified();
            trashed.folderId = stored.folderId;
            trashed.favorite = stored.favorite;
            trashed.file = trashPdf(trashed.id);
            // Commit the small recovery record before moving any document bytes. A
            // full disk or failed metadata write leaves the active PDF untouched.
            writeTrashRecord(trashed);
            if (trashed.file.exists() || !source.renameTo(trashed.file)) {
                trashIndex(trashed.id).delete();
                throw new IOException("Unable to move this PDF to Trash. The original is kept.");
            }
            records.remove(stored);
            verifiedFiles.remove(source.getName());
            // The file move is the commit. A stale active index is repaired by list().
            writeIndexBestEffort(records);
            return trashed;
        }
    }

    /** Newest deletion first, including damaged PDFs so the user can still remove them. */
    public List<TrashRecord> listTrash() throws IOException {
        synchronized (LOCK) {
            ensureTrashDirectory();
            File[] files = trashDirectory.listFiles();
            if (files == null) throw new IOException("Unable to read Trash.");
            List<TrashRecord> records = new ArrayList<>();
            Map<String, FolderRecord> folders = readFoldersIfAvailable();
            for (File candidate : files) {
                String filename = candidate.getName();
                if (!filename.endsWith(".pdf")) continue;
                String id = filename.substring(0, filename.length() - 4);
                if (!isUuid(id)) continue;
                File file = trashPdf(id);
                if (!file.isFile()) continue;
                TrashRecord record = readTrashRecord(id);
                if (record == null) {
                    // Metadata loss never deletes a retained document. It remains
                    // visible and can be restored under a fresh immutable ID.
                    record = new TrashRecord();
                    record.id = id;
                    record.originalId = UUID.randomUUID().toString();
                    record.originalFilename = record.originalId + ".pdf";
                    record.name = PdfGeometry.normalizeName("Recovered document");
                    record.createdAt = Math.max(1, file.lastModified());
                    record.deletedAt = record.createdAt;
                }
                record.file = file;
                record.sizeBytes = file.length();
                record.modifiedAt = file.lastModified();
                if (folders != null && record.folderId != null && !folders.containsKey(record.folderId)) {
                    record.folderId = null;
                }
                records.add(record);
            }
            records.sort(Comparator.comparingLong((TrashRecord value) -> value.deletedAt)
                    .reversed().thenComparing(value -> value.name, String.CASE_INSENSITIVE_ORDER));
            return records;
        }
    }

    /** Restores names, original creation time, page details, and exact PDF bytes. */
    public PdfRecord restore(String trashId) throws IOException {
        synchronized (LOCK) {
            requireTrashId(trashId);
            List<PdfRecord> active = list();
            TrashRecord trashed = findTrash(listTrash(), trashId);
            if (trashed == null) throw new IOException("This PDF is no longer in Trash.");
            int pages = pageCount(trashed.file);
            if (pages > MAX_PAGES) throw new IOException("This document has too many pages to restore.");
            File destination = privateFile(directory, trashed.originalFilename);
            // Never overwrite another PDF, even if recovery found a filename collision.
            if (destination.exists()) destination = privateFile(directory, UUID.randomUUID() + ".pdf");
            PdfRecord restored = new PdfRecord(idForFilename(destination.getName()), trashed.name,
                    pages, trashed.createdAt, destination);
            restored.sizeBytes = trashed.file.length();
            restored.modifiedAt = trashed.file.lastModified();
            restored.folderId = trashed.folderId;
            restored.favorite = trashed.favorite;
            trashed.pageCount = pages;
            trashed.restoreFilename = destination.getName();
            writeTrashRecord(trashed);
            active.add(restored);
            // Save display metadata before promotion. If storage is full, the PDF
            // stays safely in Trash; no copying or replacement is required.
            writeIndex(active);
            if (destination.exists() || !trashed.file.renameTo(destination)) {
                active.remove(restored);
                writeIndexBestEffort(active);
                throw new IOException("Unable to restore this PDF. It is still kept in Trash.");
            }
            verifiedFiles.put(destination.getName(), new VerifiedPdf(destination, pages));
            trashIndex(trashId).delete();
            return restored;
        }
    }

    /** Irreversible removal, only after the caller obtains the user's explicit choice. */
    public void deletePermanently(String trashId) throws IOException {
        synchronized (LOCK) {
            requireTrashId(trashId);
            ensureTrashDirectory();
            File file = trashPdf(trashId);
            if (file.exists() && (!file.isFile() || !file.delete())) {
                throw new IOException("Unable to permanently delete this PDF.");
            }
            // No metadata write is needed before freeing space.
            trashIndex(trashId).delete();
        }
    }

    /** Explicit caller action; never called by loading, saving, or app startup. */
    public void emptyTrash() throws IOException {
        synchronized (LOCK) {
            ensureTrashDirectory();
            File[] files = trashDirectory.listFiles();
            if (files == null) throw new IOException("Unable to read Trash.");
            for (File file : files) {
                String filename = file.getName();
                if (!filename.endsWith(".pdf")) continue;
                String id = filename.substring(0, filename.length() - 4);
                if (isUuid(id)) deletePermanently(id);
            }
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
        requirePrivateDirectory(directory, "pdfs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create the PDF library.");
        }
    }

    private void ensureTrashDirectory() throws IOException {
        requirePrivateDirectory(trashDirectory, "pdf_trash");
        if (!trashDirectory.isDirectory() && !trashDirectory.mkdirs()) {
            throw new IOException("Unable to create Trash. The original PDF is kept.");
        }
    }

    private void requirePrivateDirectory(File folder, String name) throws IOException {
        File expected = new File(context.getFilesDir().getCanonicalFile(), name);
        if (!folder.getCanonicalFile().equals(expected)) {
            throw new IOException("The document storage path is unavailable.");
        }
    }

    private static File privateFile(File folder, String filename) throws IOException {
        if (filename == null || filename.isEmpty() || filename.contains("/")
                || filename.contains("\\") || filename.equals(".") || filename.equals("..")) {
            throw new IOException("Invalid document identifier.");
        }
        File file = new File(folder.getCanonicalFile(), filename);
        if (!file.getCanonicalFile().equals(file)) {
            throw new IOException("The document storage path is unavailable.");
        }
        return file;
    }

    private static void requireTrashId(String id) throws IOException {
        if (id == null || !isUuid(id)) throw new IOException("Invalid Trash identifier.");
    }

    private File trashPdf(String id) throws IOException {
        requireTrashId(id);
        return privateFile(trashDirectory, id + ".pdf");
    }

    private AtomicFile trashIndex(String id) throws IOException {
        requireTrashId(id);
        return new AtomicFile(privateFile(trashDirectory, id + ".json"));
    }

    private static TrashRecord findTrash(List<TrashRecord> records, String id) {
        for (TrashRecord record : records) if (record.id.equals(id)) return record;
        return null;
    }

    private TrashRecord readTrashRecord(String id) throws IOException {
        try (FileInputStream input = trashIndex(id).openRead();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > 16 * 1024) return null;
                bytes.write(buffer, 0, read);
            }
            JSONObject object = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            String filename = object.getString("filename");
            if (!filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) return null;
            privateFile(directory, filename);
            TrashRecord record = new TrashRecord();
            record.id = id;
            record.originalFilename = filename;
            record.originalId = idForFilename(filename);
            record.name = PdfGeometry.normalizeName(object.optString("name", "Document"));
            record.pageCount = Math.max(0, object.optInt("pages", 0));
            record.createdAt = Math.max(1, object.optLong("created", 1));
            record.deletedAt = Math.max(1, object.optLong("deleted", 1));
            record.sizeBytes = Math.max(0, object.optLong("bytes", 0));
            record.modifiedAt = object.optLong("modified", 0);
            record.folderId = optionalFolderId(object);
            record.favorite = object.optBoolean("favorite", false);
            String restore = object.optString("restoreFilename", "");
            if (!restore.isEmpty()) {
                if (!restore.toLowerCase(Locale.ROOT).endsWith(".pdf")) return null;
                privateFile(directory, restore);
                record.restoreFilename = restore;
            }
            record.file = trashPdf(id);
            return record;
        } catch (JSONException | RuntimeException invalid) {
            return null;
        } catch (IOException unavailable) {
            // Invalid or unavailable metadata is recoverable from the retained PDF.
            return null;
        }
    }

    private void writeTrashRecord(TrashRecord record) throws IOException {
        try {
            JSONObject object = new JSONObject();
            object.put("version", 1);
            object.put("filename", record.originalFilename);
            object.put("name", record.name);
            object.put("pages", record.pageCount);
            object.put("created", record.createdAt);
            object.put("deleted", record.deletedAt);
            object.put("bytes", record.sizeBytes);
            object.put("modified", record.modifiedAt);
            object.put("folderId", record.folderId == null ? JSONObject.NULL : record.folderId);
            object.put("favorite", record.favorite);
            if (record.restoreFilename != null) object.put("restoreFilename", record.restoreFilename);
            byte[] encoded = object.toString().getBytes(StandardCharsets.UTF_8);
            AtomicFile atomic = trashIndex(record.id);
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(encoded);
                output.getFD().sync();
                atomic.finishWrite(output);
                verifyAtomicFile(atomic, encoded);
            } catch (IOException failure) {
                if (output != null) atomic.failWrite(output);
                throw failure;
            }
        } catch (JSONException error) {
            throw new IOException("Unable to save Trash details. The original PDF is kept.", error);
        }
    }

    /** Restores display facts if a process died after promotion but before journal cleanup. */
    private boolean recoverTrashRestores(Map<String, PdfRecord> metadata) throws IOException {
        if (!trashDirectory.isDirectory()) return false;
        try { requirePrivateDirectory(trashDirectory, "pdf_trash"); }
        catch (IOException unavailable) { return false; }
        File[] files = trashDirectory.listFiles();
        // Trash storage trouble must not hide the healthy active library.
        if (files == null) return false;
        boolean changed = false;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".json")) continue;
            String id = name.substring(0, name.length() - 5);
            if (!isUuid(id)) continue;
            try { if (trashPdf(id).exists()) continue; }
            catch (IOException unsafePath) { continue; }
            TrashRecord record = readTrashRecord(id);
            if (record == null) continue;
            String target = record.restoreFilename == null ? record.originalFilename : record.restoreFilename;
            File restored = privateFile(directory, target);
            if (!restored.isFile() || metadata.containsKey(target)
                    || restored.length() != record.sizeBytes
                    || restored.lastModified() != record.modifiedAt) continue;
            PdfRecord recovered = new PdfRecord(idForFilename(target), record.name, record.pageCount,
                    record.createdAt, restored);
            recovered.sizeBytes = record.sizeBytes;
            recovered.modifiedAt = record.modifiedAt;
            recovered.folderId = record.folderId;
            recovered.favorite = record.favorite;
            metadata.put(target, recovered);
            changed = true;
        }
        return changed;
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
                record.folderId = optionalFolderId(object);
                record.favorite = object.optBoolean("favorite", false);
                if (record.createdAt <= 0) record.createdAt = Math.max(1, record.file.lastModified());
                records.put(filename, record);
            }
            return records;
        } catch (IOException | JSONException | RuntimeException ignored) {
            return null;
        }
    }

    private static String optionalFolderId(JSONObject object) {
        String id = object.optString("folderId", "");
        return isUuid(id) ? id : null;
    }

    private static void requireFolderId(String id) throws IOException {
        if (id == null || !isUuid(id)) throw new IOException("Invalid folder identifier.");
    }

    private static String normalizeFolderName(String name) throws IOException {
        if (name == null) throw new IOException("Enter a folder name.");
        int first = 0;
        int last = name.length();
        while (first < last) {
            int point = name.codePointAt(first);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) break;
            first += Character.charCount(point);
        }
        while (last > first) {
            int point = name.codePointBefore(last);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) break;
            last -= Character.charCount(point);
        }
        String normalized = Normalizer.normalize(name.substring(first, last), Normalizer.Form.NFC);
        if (normalized.isEmpty()) throw new IOException("Enter a folder name.");
        if (normalized.codePointCount(0, normalized.length()) > 80) {
            throw new IOException("Use a folder name of 80 characters or fewer.");
        }
        for (int offset = 0; offset < normalized.length();) {
            int point = normalized.codePointAt(offset);
            if (Character.isISOControl(point) || point == '/' || point == '\\'
                    || (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE)) {
                throw new IOException("Use a folder name without slashes or control characters.");
            }
            offset += Character.charCount(point);
        }
        return normalized;
    }

    private static void requireUniqueFolderName(Map<String, FolderRecord> folders,
                                                String name, String exceptId) throws IOException {
        for (FolderRecord folder : folders.values()) {
            if (!folder.id.equals(exceptId) && folder.name.equalsIgnoreCase(name)) {
                throw new IOException("A folder with this name already exists.");
            }
        }
    }

    private Map<String, FolderRecord> readFoldersIfAvailable() {
        try { return readFolders(); }
        catch (IOException unavailable) {
            // Folder trouble must not hide PDFs or discard their saved membership.
            return null;
        }
    }

    private Map<String, FolderRecord> readFolders() throws IOException {
        requirePrivateDirectory(directory, "pdfs");
        File base = folderIndex.getBaseFile();
        if (!base.exists() && !new File(base.getPath() + ".bak").exists()) {
            return new HashMap<>();
        }
        try (FileInputStream input = folderIndex.openRead();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > MAX_INDEX_BYTES) {
                    throw new IOException("Folder details are too large to read.");
                }
                bytes.write(buffer, 0, read);
            }
            JSONArray values = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
                    .getJSONArray("folders");
            if (values.length() > 1000) throw new IOException("Folder details are too large to read.");
            Map<String, FolderRecord> folders = new HashMap<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                String id = value.getString("id");
                requireFolderId(id);
                String name = normalizeFolderName(value.getString("name"));
                if (folders.containsKey(id)) throw new IOException("Duplicate folder identifier.");
                requireUniqueFolderName(folders, name, null);
                folders.put(id, new FolderRecord(id, name, Math.max(1, value.optLong("created", 1))));
            }
            return folders;
        } catch (JSONException | RuntimeException invalid) {
            throw new IOException("Folder details could not be read. Your PDFs are kept.", invalid);
        }
    }

    private void writeFolders(Map<String, FolderRecord> folders) throws IOException {
        try {
            JSONArray array = new JSONArray();
            List<FolderRecord> ordered = new ArrayList<>(folders.values());
            ordered.sort(Comparator.comparing(folder -> folder.id));
            for (FolderRecord folder : ordered) {
                JSONObject value = new JSONObject();
                value.put("id", folder.id);
                value.put("name", folder.name);
                value.put("created", folder.createdAt);
                array.put(value);
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("folders", array);
            byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_INDEX_BYTES) throw new IOException("The folder library is full.");
            FileOutputStream output = null;
            try {
                output = folderIndex.startWrite();
                output.write(encoded);
                output.getFD().sync();
                folderIndex.finishWrite(output);
                verifyAtomicFile(folderIndex, encoded);
            } catch (IOException failure) {
                if (output != null) folderIndex.failWrite(output);
                throw failure;
            }
        } catch (JSONException invalid) {
            throw new IOException("Unable to save folder details. Your PDFs are kept.", invalid);
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
                object.put("folderId", record.folderId == null ? JSONObject.NULL : record.folderId);
                object.put("favorite", record.favorite);
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
        verifyAtomicFile(index, expected);
    }

    private static void verifyAtomicFile(AtomicFile atomic, byte[] expected) throws IOException {
        try (FileInputStream input = atomic.openRead()) {
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

    /** Recover prior staged deletions and discard copies abandoned by process death. */
    private void recoverInterruptedOperations(Map<String, PdfRecord> metadata) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Unable to read the PDF library.");
        for (File removed : files) {
            String name = removed.getName();
            // save() holds LOCK throughout copying and promotion, so a recognized
            // .part encountered by list() cannot belong to an active save.
            if (removed.isFile() && name.endsWith(".part")
                    && isUuid(name.substring(0, name.length() - ".part".length()))) {
                removed.delete();
                continue;
            }
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

    private int pageCount(File file) throws IOException {
        if (android.os.Build.VERSION.SDK_INT < 28) return LegacyPdfProbe.pageCount(context, file);
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
