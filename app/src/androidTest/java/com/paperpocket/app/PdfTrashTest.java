package com.paperpocket.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Paint;
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
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests actual PDF bytes, restart recovery, and failure boundaries of private Trash. */
@RunWith(AndroidJUnit4.class)
public class PdfTrashTest {
    private File root;
    private Context context;
    private File source;

    @Before
    public void createLibrary() throws IOException {
        Context application = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        root = new File(application.getCacheDir(), "pdf-trash-" + UUID.randomUUID());
        context = new IsolatedContext(application, root);
        source = new File(context.getCacheDir(), "original.pdf");
        writePdf(source, Color.RED, 2);
    }

    @After
    public void removeLibrary() {
        if (root != null) deleteTree(root);
    }

    @Test
    public void moveRestartAndRestorePreserveBytesAndDocumentDetails() throws IOException {
        PdfStore store = new PdfStore(context);
        PdfRecord saved = store.save(source, "Original name", 2);
        store.rename(saved, "Renamed receipts");
        byte[] original = readBytes(source);
        long created = saved.createdAt;

        TrashRecord trashed = store.moveToTrash(saved);
        assertFalse(saved.file.exists());
        assertTrue(store.list().isEmpty());
        assertArrayEquals(original, readBytes(source));
        assertArrayEquals(original, readBytes(trashed.file));

        List<TrashRecord> records = new PdfStore(context).listTrash();
        assertEquals(1, records.size());
        TrashRecord restarted = records.get(0);
        assertEquals(trashed.id, restarted.id);
        assertEquals(saved.id, restarted.originalId);
        assertEquals("Renamed receipts.pdf", restarted.name);
        assertEquals(2, restarted.pageCount);
        assertEquals(created, restarted.createdAt);
        assertEquals(original.length, restarted.sizeBytes);
        assertEquals(trashed.deletedAt, restarted.deletedAt);

        PdfRecord restored = new PdfStore(context).restore(restarted.id);
        assertEquals(saved.id, restored.id);
        assertEquals("Renamed receipts.pdf", restored.name);
        assertEquals(2, restored.pageCount);
        assertEquals(created, restored.createdAt);
        assertArrayEquals(original, readBytes(restored.file));
        assertTrue(new PdfStore(context).listTrash().isEmpty());
        List<PdfRecord> active = new PdfStore(context).list();
        assertEquals(1, active.size());
        assertEquals(restored.name, active.get(0).name);
        assertArrayEquals(original, readBytes(source));
    }

    @Test
    public void repeatedUndoDoesNotDuplicateOrAlterPdf() throws IOException {
        PdfStore store = new PdfStore(context);
        PdfRecord saved = store.save(source, "Keep this", 2);
        byte[] original = readBytes(saved.file);
        for (int attempt = 0; attempt < 3; attempt++) {
            TrashRecord trashed = new PdfStore(context).moveToTrash(saved);
            assertTrue(new PdfStore(context).list().isEmpty());
            saved = new PdfStore(context).restore(trashed.id);
            assertArrayEquals(original, readBytes(saved.file));
            assertEquals(1, new PdfStore(context).list().size());
            assertTrue(new PdfStore(context).listTrash().isEmpty());
        }
    }

    @Test
    public void permanentDeleteAndEmptyTrashLeaveActiveLibraryUntouched() throws IOException {
        PdfStore store = new PdfStore(context);
        PdfRecord keep = store.save(source, "Active document", 2);
        TrashRecord first = store.moveToTrash(store.save(source, "First discarded document", 2));
        TrashRecord second = store.moveToTrash(store.save(source, "Second discarded document", 2));
        byte[] activeBytes = readBytes(keep.file);
        byte[] secondBytes = readBytes(second.file);

        new PdfStore(context).deletePermanently(first.id);
        assertFalse(first.file.exists());
        List<TrashRecord> remaining = new PdfStore(context).listTrash();
        assertEquals(1, remaining.size());
        assertEquals(second.id, remaining.get(0).id);
        assertArrayEquals(secondBytes, readBytes(second.file));
        assertArrayEquals(activeBytes, readBytes(keep.file));

        new PdfStore(context).emptyTrash();
        assertTrue(new PdfStore(context).listTrash().isEmpty());
        assertFalse(second.file.exists());
        assertArrayEquals(activeBytes, readBytes(keep.file));
        assertEquals(1, new PdfStore(context).list().size());
        assertTrue(source.isFile());
    }

    @Test
    public void unsafeIdentifiersCannotRestoreOrDeleteAnyFile() throws IOException {
        PdfStore store = new PdfStore(context);
        PdfRecord active = store.save(source, "Active", 2);
        TrashRecord trashed = store.moveToTrash(store.save(source, "Trashed", 2));
        byte[] sourceBytes = readBytes(source);
        byte[] activeBytes = readBytes(active.file);
        byte[] trashBytes = readBytes(trashed.file);
        String[] invalid = {null, "", "..", "../original", "../" + trashed.id,
                trashed.id + "/../original", "not-a-uuid", "1-1-1-1-1"};
        for (String id : invalid) {
            try { store.restore(id); fail("Unsafe restore ID was accepted: " + id); }
            catch (IOException expected) { }
            try { store.deletePermanently(id); fail("Unsafe delete ID was accepted: " + id); }
            catch (IOException expected) { }
        }
        assertArrayEquals(sourceBytes, readBytes(source));
        assertArrayEquals(activeBytes, readBytes(active.file));
        assertArrayEquals(trashBytes, readBytes(trashed.file));
        assertEquals(1, store.list().size());
        assertEquals(1, store.listTrash().size());
    }

    @Test
    public void unavailableTrashStorageLeavesOriginalInLibrary() throws IOException {
        PdfStore store = new PdfStore(context);
        PdfRecord saved = store.save(source, "Keep on failure", 2);
        byte[] original = readBytes(saved.file);
        File obstruction = new File(context.getFilesDir(), "pdf_trash");
        try (FileOutputStream output = new FileOutputStream(obstruction)) { output.write(1); }

        try { store.moveToTrash(saved); fail("Unavailable Trash must reject the move."); }
        catch (IOException expected) { }
        assertArrayEquals(original, readBytes(saved.file));
        List<PdfRecord> active = new PdfStore(context).list();
        assertEquals(1, active.size());
        assertEquals("Keep on failure.pdf", active.get(0).name);
    }

    @Test
    public void failedTrashMetadataWriteLeavesOriginalBytesAndNameUntouched() throws Exception {
        PdfStore store = new PdfStore(context);
        PdfRecord saved = store.save(source, "Keep when metadata cannot be saved", 2);
        byte[] original = readBytes(saved.file);
        File trash = new File(context.getFilesDir(), "pdf_trash");
        assertTrue(trash.mkdir());
        // Deny creation of the recovery record without filling the device's disk.
        Os.chmod(trash.getAbsolutePath(), 0500);
        try {
            try { store.moveToTrash(saved); fail("A failed recovery-record write must reject the move."); }
            catch (IOException expected) { }
            assertArrayEquals(original, readBytes(saved.file));
        } finally {
            Os.chmod(trash.getAbsolutePath(), 0700);
        }
        List<PdfRecord> active = new PdfStore(context).list();
        assertEquals(1, active.size());
        assertEquals(saved.name, active.get(0).name);
        assertTrue(new PdfStore(context).listTrash().isEmpty());
    }

    @Test
    public void failedRestoreMetadataCommitLeavesPdfRecoverableInTrash() throws IOException {
        PdfStore store = new PdfStore(context);
        PdfRecord saved = store.save(source, "Keep despite full storage", 2);
        TrashRecord trashed = store.moveToTrash(saved);
        byte[] retained = readBytes(trashed.file);
        blockActiveIndexWrites();

        try { new PdfStore(context).restore(trashed.id); fail("An uncommitted restore must fail."); }
        catch (IOException expected) { }
        assertFalse(saved.file.exists());
        assertArrayEquals(retained, readBytes(trashed.file));
        List<TrashRecord> after = new PdfStore(context).listTrash();
        assertEquals(1, after.size());
        assertEquals("Keep despite full storage.pdf", after.get(0).name);
        // Permanent removal must still free space without needing an index write.
        new PdfStore(context).deletePermanently(trashed.id);
        assertFalse(trashed.file.exists());
        assertTrue(new PdfStore(context).listTrash().isEmpty());
    }

    @Test
    public void interruptedRestoreRecoversDisplayMetadataAfterIndexLoss() throws Exception {
        PdfRecord saved = new PdfStore(context).save(source, "Interrupted restore name", 2);
        TrashRecord trashed = new PdfStore(context).moveToTrash(saved);
        File journal = trashJournal(trashed.id);
        JSONObject metadata = new JSONObject(new String(new AtomicFile(journal).readFully(),
                StandardCharsets.UTF_8));
        metadata.put("restoreFilename", saved.file.getName());
        replaceAtomic(journal, metadata.toString());
        assertTrue(trashed.file.renameTo(saved.file));
        new AtomicFile(activeIndex()).delete();

        List<PdfRecord> recovered = new PdfStore(context).list();
        assertEquals(1, recovered.size());
        PdfRecord restored = recovered.get(0);
        assertEquals(saved.id, restored.id);
        assertEquals("Interrupted restore name.pdf", restored.name);
        assertEquals(saved.createdAt, restored.createdAt);
        assertEquals(2, restored.pageCount);
        assertArrayEquals(readBytes(source), readBytes(restored.file));
        assertTrue(new PdfStore(context).listTrash().isEmpty());
        assertEquals(restored.name, new PdfStore(context).list().get(0).name);
    }

    @Test
    public void preparedTrashRecordDoesNotRemoveActivePdfOnRestart() throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Prepared move", 2);
        TrashRecord trashed = new PdfStore(context).moveToTrash(saved);
        // Models a committed recovery record before the corresponding file move.
        assertTrue(trashed.file.renameTo(saved.file));
        new AtomicFile(activeIndex()).delete();

        List<PdfRecord> active = new PdfStore(context).list();
        assertEquals(1, active.size());
        assertEquals(saved.id, active.get(0).id);
        assertEquals("Prepared move.pdf", active.get(0).name);
        assertArrayEquals(readBytes(source), readBytes(saved.file));
        assertTrue(new PdfStore(context).listTrash().isEmpty());
    }

    @Test
    public void restoreCollisionPreservesBothDocuments() throws IOException {
        PdfStore store = new PdfStore(context);
        PdfRecord saved = store.save(source, "Restore this scan", 2);
        TrashRecord trashed = store.moveToTrash(saved);
        byte[] original = readBytes(trashed.file);
        writePdf(saved.file, Color.BLUE, 1);
        byte[] colliding = readBytes(saved.file);

        PdfRecord restored = new PdfStore(context).restore(trashed.id);
        assertNotEquals(saved.id, restored.id);
        assertEquals("Restore this scan.pdf", restored.name);
        assertArrayEquals(original, readBytes(restored.file));
        assertArrayEquals(colliding, readBytes(saved.file));
        assertEquals(2, new PdfStore(context).list().size());
        assertTrue(new PdfStore(context).listTrash().isEmpty());
    }

    private File activeIndex() { return new File(context.getFilesDir(), "pdfs/index.json"); }
    private File trashJournal(String id) { return new File(context.getFilesDir(), "pdf_trash/" + id + ".json"); }

    private void blockActiveIndexWrites() throws IOException {
        new AtomicFile(activeIndex()).delete();
        for (String suffix : new String[] {"", ".new", ".bak"}) {
            File blocked = new File(activeIndex().getPath() + suffix);
            assertTrue(blocked.mkdir());
            try (FileOutputStream output = new FileOutputStream(new File(blocked, "blocker"))) {
                output.write(1);
            }
        }
    }

    private static void replaceAtomic(File file, String text) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (IOException failure) {
            atomic.failWrite(output);
            throw failure;
        }
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

    private static void writePdf(File destination, int color, int pages) throws IOException {
        PdfDocument document = new PdfDocument();
        try {
            Paint paint = new Paint();
            paint.setColor(color);
            for (int index = 0; index < pages; index++) {
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(200, 300, index + 1).create());
                page.getCanvas().drawColor(Color.WHITE);
                page.getCanvas().drawRect(20, 20, 180, 280, paint);
                document.finishPage(page);
            }
            try (FileOutputStream output = new FileOutputStream(destination)) {
                document.writeTo(output);
                output.getFD().sync();
            }
        } finally {
            document.close();
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
