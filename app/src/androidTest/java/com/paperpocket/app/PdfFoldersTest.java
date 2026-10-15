package com.paperpocket.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.system.Os;
import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
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
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Checks persistent organization against real PDFs, legacy metadata, and interrupted writes. */
@RunWith(AndroidJUnit4.class)
public class PdfFoldersTest {
    private File root;
    private Context context;
    private File source;

    @Before
    public void createLibrary() throws IOException {
        Context application = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        root = new File(application.getCacheDir(), "pdf-folders-" + UUID.randomUUID());
        context = new IsolatedContext(application, root);
        source = new File(context.getCacheDir(), "source.pdf");
        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(200, 300, 1).create());
            page.getCanvas().drawColor(Color.BLUE);
            document.finishPage(page);
            try (FileOutputStream output = new FileOutputStream(source)) {
                document.writeTo(output);
                output.getFD().sync();
            }
        } finally { document.close(); }
    }

    @After
    public void removeLibrary() { if (root != null) deleteTree(root); }

    @Test
    public void folderAndFavoriteSurviveRenameRestartAndMoveToRoot() throws IOException {
        PdfStore store = new PdfStore(context);
        PdfRecord saved = store.save(source, "Receipt", 1);
        FolderRecord folder = store.createFolder("  Receipts  ");
        byte[] original = readBytes(saved.file);
        store.moveToFolder(saved, folder.id);
        store.setFavorite(saved, true);
        store.rename(saved, "My receipt");
        store.renameFolder(folder.id, "Expenses");

        PdfStore restarted = new PdfStore(context);
        PdfRecord retained = restarted.list().get(0);
        assertEquals(folder.id, retained.folderId);
        assertTrue(retained.favorite);
        assertEquals("My receipt.pdf", retained.name);
        assertEquals(saved.id, retained.id);
        assertArrayEquals(original, readBytes(retained.file));
        FolderRecord renamed = restarted.listFolders().get(0);
        assertEquals(folder.id, renamed.id);
        assertEquals(folder.createdAt, renamed.createdAt);
        assertEquals("Expenses", renamed.name);

        restarted.moveToFolder(retained, null);
        restarted.setFavorite(retained, false);
        PdfRecord rootRecord = new PdfStore(context).list().get(0);
        assertNull(rootRecord.folderId);
        assertFalse(rootRecord.favorite);
        assertArrayEquals(original, readBytes(rootRecord.file));
    }

    @Test
    public void version12MetadataDefaultsToRootAndNotFavorite() throws Exception {
        PdfRecord saved = new PdfStore(context).save(source, "Before folders", 1);
        JSONObject index = readJson(activeIndex());
        JSONObject document = index.getJSONArray("documents").getJSONObject(0);
        document.remove("folderId");
        document.remove("favorite");
        replaceAtomic(activeIndex(), index.toString());

        PdfRecord migrated = new PdfStore(context).list().get(0);
        assertNull(migrated.folderId);
        assertFalse(migrated.favorite);
        assertEquals(saved.id, migrated.id);
        assertEquals(saved.name, migrated.name);
        assertTrue(new PdfStore(context).listFolders().isEmpty());
        assertArrayEquals(readBytes(source), readBytes(migrated.file));
    }

    @Test
    public void trashAndRestartRestoreKeepFolderAndFavorite() throws IOException {
        PdfStore store = new PdfStore(context);
        FolderRecord folder = store.createFolder("Travel");
        PdfRecord saved = store.save(source, "Tickets", 1);
        store.moveToFolder(saved, folder.id);
        store.setFavorite(saved, true);
        TrashRecord trashed = store.moveToTrash(saved);
        TrashRecord restarted = new PdfStore(context).listTrash().get(0);
        assertEquals(folder.id, restarted.folderId);
        assertTrue(restarted.favorite);
        assertArrayEquals(readBytes(source), readBytes(restarted.file));

        PdfRecord restored = new PdfStore(context).restore(trashed.id);
        assertEquals(folder.id, restored.folderId);
        assertTrue(restored.favorite);
        PdfRecord persisted = new PdfStore(context).list().get(0);
        assertEquals(folder.id, persisted.folderId);
        assertTrue(persisted.favorite);
    }

    @Test
    public void interruptedRestoreRecoversOrganizationAlongWithDisplayMetadata() throws Exception {
        PdfStore store = new PdfStore(context);
        FolderRecord folder = store.createFolder("Archive");
        PdfRecord saved = store.save(source, "Keep the name", 1);
        store.moveToFolder(saved, folder.id);
        store.setFavorite(saved, true);
        TrashRecord trashed = store.moveToTrash(saved);
        JSONObject recovery = readJson(trashJournal(trashed.id));
        recovery.put("restoreFilename", saved.file.getName());
        replaceAtomic(trashJournal(trashed.id), recovery.toString());
        assertTrue(trashed.file.renameTo(saved.file));
        new AtomicFile(activeIndex()).delete();

        PdfRecord recovered = new PdfStore(context).list().get(0);
        assertEquals(folder.id, recovered.folderId);
        assertTrue(recovered.favorite);
        assertEquals(saved.name, recovered.name);
        assertArrayEquals(readBytes(source), readBytes(recovered.file));
        assertEquals(folder.id, new PdfStore(context).list().get(0).folderId);
    }

    @Test
    public void deletingFolderMovesActiveAndTrashToRootWithoutChangingAnyPdf() throws IOException {
        PdfStore store = new PdfStore(context);
        FolderRecord removed = store.createFolder("Temporary folder");
        FolderRecord kept = store.createFolder("Keep folder");
        PdfRecord active = store.save(source, "Active", 1);
        PdfRecord discarded = store.save(source, "Trashed", 1);
        PdfRecord elsewhere = store.save(source, "Elsewhere", 1);
        store.moveToFolder(active, removed.id);
        store.moveToFolder(discarded, removed.id);
        store.moveToFolder(elsewhere, kept.id);
        store.setFavorite(active, true);
        store.setFavorite(discarded, true);
        TrashRecord trashed = store.moveToTrash(discarded);
        byte[] activeBytes = readBytes(active.file);
        byte[] trashBytes = readBytes(trashed.file);
        byte[] otherBytes = readBytes(elsewhere.file);
        // Deletion only commits the folder registry. The untouched active index and
        // Trash journal model a process stopping before any optional stale-ID cleanup.
        store.deleteFolder(removed.id);

        PdfStore restarted = new PdfStore(context);
        assertEquals(1, restarted.listFolders().size());
        for (PdfRecord record : restarted.list()) {
            if (record.id.equals(active.id)) {
                assertNull(record.folderId);
                assertTrue(record.favorite);
            } else assertEquals(kept.id, record.folderId);
        }
        TrashRecord retained = restarted.listTrash().get(0);
        assertNull(retained.folderId);
        assertTrue(retained.favorite);
        assertArrayEquals(activeBytes, readBytes(active.file));
        assertArrayEquals(trashBytes, readBytes(trashed.file));
        assertArrayEquals(otherBytes, readBytes(elsewhere.file));
        PdfRecord restored = restarted.restore(trashed.id);
        assertNull(restored.folderId);
        assertTrue(restored.favorite);
        assertArrayEquals(trashBytes, readBytes(restored.file));
    }

    @Test
    public void namesHandleUnicodeAndRejectDuplicatesAndInvalidValues() throws IOException {
        PdfStore store = new PdfStore(context);
        FolderRecord first = store.createFolder("  Cafe\u0301 \uD83D\uDCC4  ");
        assertEquals("Caf\u00e9 \uD83D\uDCC4", first.name);
        assertRejected(() -> store.createFolder("CAF\u00c9 \uD83D\uDCC4"));
        FolderRecord second = store.createFolder("Bills");
        assertRejected(() -> store.renameFolder(second.id, "caf\u00e9 \uD83D\uDCC4"));
        for (String invalid : new String[] {null, "", "\u00a0  ", "../folder", "a\\b", "bad\nname", "\uD800"}) {
            assertRejected(() -> store.createFolder(invalid));
        }
        StringBuilder limit = new StringBuilder();
        for (int i = 0; i < 80; i++) limit.append("\uD83D\uDCC4");
        assertEquals(limit.toString(), store.createFolder(limit.toString()).name);
        assertRejected(() -> store.createFolder(limit + "\uD83D\uDCC4"));
        assertEquals(3, new PdfStore(context).listFolders().size());
        assertEquals("Bills", new PdfStore(context).listFolders().get(0).name);
    }

    @Test
    public void invalidAndStaleFolderIdsCannotChangeMetadataOrAccessPaths() throws IOException {
        PdfStore store = new PdfStore(context);
        FolderRecord folder = store.createFolder("Safe");
        PdfRecord saved = store.save(source, "Keep", 1);
        store.moveToFolder(saved, folder.id);
        store.setFavorite(saved, true);
        for (String invalid : new String[] {"", "..", "../index", "1-1-1-1-1", UUID.randomUUID().toString()}) {
            assertRejected(() -> store.moveToFolder(saved, invalid));
            assertRejected(() -> store.renameFolder(invalid, "Changed"));
            assertRejected(() -> store.deleteFolder(invalid));
        }
        assertRejected(() -> store.renameFolder(null, "Changed"));
        assertRejected(() -> store.deleteFolder(null));
        PdfRecord retained = new PdfStore(context).list().get(0);
        assertEquals(folder.id, retained.folderId);
        assertTrue(retained.favorite);
        assertEquals("Safe", new PdfStore(context).listFolders().get(0).name);
        assertArrayEquals(readBytes(source), readBytes(retained.file));
    }

    @Test
    public void failedFolderAndFavoriteWritesPreserveCommittedMetadata() throws Exception {
        PdfStore store = new PdfStore(context);
        FolderRecord folder = store.createFolder("Committed");
        PdfRecord saved = store.save(source, "Keep", 1);
        store.moveToFolder(saved, folder.id);
        store.setFavorite(saved, true);
        File directory = activeIndex().getParentFile();
        Os.chmod(activeIndex().getAbsolutePath(), 0400);
        Os.chmod(folderIndex().getAbsolutePath(), 0400);
        Os.chmod(directory.getAbsolutePath(), 0500);
        try {
            assertRejected(() -> store.renameFolder(folder.id, "Uncommitted"));
            assertRejected(() -> store.deleteFolder(folder.id));
            assertRejected(() -> store.createFolder("Uncommitted new folder"));
            assertRejected(() -> store.setFavorite(saved, false));
            assertRejected(() -> store.moveToFolder(saved, null));
            assertTrue(saved.favorite);
            assertEquals(folder.id, saved.folderId);
        } finally {
            Os.chmod(directory.getAbsolutePath(), 0700);
            Os.chmod(activeIndex().getAbsolutePath(), 0600);
            Os.chmod(folderIndex().getAbsolutePath(), 0600);
        }
        PdfStore restarted = new PdfStore(context);
        assertEquals("Committed", restarted.listFolders().get(0).name);
        PdfRecord retained = restarted.list().get(0);
        assertEquals(folder.id, retained.folderId);
        assertTrue(retained.favorite);
        assertArrayEquals(readBytes(source), readBytes(retained.file));
    }

    @Test
    public void corruptFolderMetadataDoesNotHideOrClearPdfOrganization() throws IOException {
        PdfStore store = new PdfStore(context);
        FolderRecord folder = store.createFolder("Saved folder");
        PdfRecord saved = store.save(source, "Still accessible", 1);
        store.moveToFolder(saved, folder.id);
        store.setFavorite(saved, true);
        replaceAtomic(folderIndex(), "{invalid folder registry");
        PdfStore restarted = new PdfStore(context);
        assertRejected(restarted::listFolders);
        assertRejected(() -> restarted.createFolder("Do not overwrite corrupt metadata"));
        PdfRecord accessible = restarted.list().get(0);
        assertEquals(folder.id, accessible.folderId);
        assertTrue(accessible.favorite);
        assertArrayEquals(readBytes(source), readBytes(accessible.file));
    }

    private File activeIndex() { return new File(context.getFilesDir(), "pdfs/index.json"); }
    private File folderIndex() { return new File(context.getFilesDir(), "pdfs/folders.json"); }
    private File trashJournal(String id) { return new File(context.getFilesDir(), "pdf_trash/" + id + ".json"); }

    private static JSONObject readJson(File file) throws Exception {
        return new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
    }

    private static void replaceAtomic(File file, String text) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (IOException failure) { atomic.failWrite(output); throw failure; }
    }

    private interface Action { void run() throws IOException; }
    private static void assertRejected(Action action) throws IOException {
        try { action.run(); fail("The invalid or uncommitted change was accepted."); }
        catch (IOException expected) { }
    }

    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
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
            if (!files.mkdirs() || !cache.mkdirs()) throw new IOException("Unable to create test storage.");
        }
        @Override public File getFilesDir() { return files; }
        @Override public File getCacheDir() { return cache; }
        @Override public Context getApplicationContext() { return this; }
    }
}
