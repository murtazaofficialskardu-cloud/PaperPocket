package com.paperpocket.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Reproduces filesystem states left by interrupted saves and deletions. */
@RunWith(AndroidJUnit4.class)
public class PdfStoreRecoveryTest {
    private File root;
    private Context context;
    private File source;

    @Before
    public void createIsolatedLibrary() throws IOException {
        Context application = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        root = new File(application.getCacheDir(), "pdf-recovery-" + UUID.randomUUID());
        context = new IsolatedContext(application, root);
        File first = new File(context.getFilesDir(), "first.png");
        File second = new File(context.getFilesDir(), "second.png");
        writeImage(first, Color.RED);
        writeImage(second, Color.BLUE);
        source = PdfConverter.convert(context, Arrays.asList(
                new PageItem(first.getAbsolutePath()), new PageItem(second.getAbsolutePath())),
                new PdfConverter.Options(), null);
    }

    @After
    public void removeIsolatedLibrary() {
        if (root != null) deleteTree(root);
    }

    @Test
    public void indexedTombstoneRestoresDocumentAfterInterruptedDeletion() throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Keep this scan", 2);
        File tombstone = stageDeletion(saved);

        // The process stopped after moving the PDF, before removing its index entry.
        List<PdfRecord> recovered = new PdfStore(context).list();
        assertEquals(1, recovered.size());
        PdfRecord restored = recovered.get(0);
        assertEquals(saved.id, restored.id);
        assertEquals("Keep this scan.pdf", restored.name);
        assertEquals(2, restored.pageCount);
        assertEquals(saved.file, restored.file);
        assertTrue(restored.file.isFile());
        assertFalse(tombstone.exists());
        assertEquals(1, new PdfStore(context).list().size());
    }

    @Test
    public void committedDeletionRemovesRemainingTombstone() throws Exception {
        PdfRecord saved = new PdfStore(context).save(source, "Delete this scan", 2);
        File tombstone = stageDeletion(saved);
        JSONObject index = new JSONObject(new String(new AtomicFile(indexFile()).readFully(),
                StandardCharsets.UTF_8));
        index.put("documents", new JSONArray());
        replaceIndex(index.toString());

        // The index commit succeeded; only final deletion of the staged file was interrupted.
        assertTrue(new PdfStore(context).list().isEmpty());
        assertFalse(tombstone.exists());
        assertFalse(saved.file.exists());
        assertTrue(new PdfStore(context).list().isEmpty());
    }

    @Test
    public void missingIndexRestoresTombstoneConservatively() throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Recover this scan", 2);
        File tombstone = stageDeletion(saved);
        new AtomicFile(indexFile()).delete();

        // Without the index, a deletion commit cannot be distinguished from metadata loss.
        List<PdfRecord> recovered = new PdfStore(context).list();
        assertEquals(1, recovered.size());
        assertEquals(saved.id, recovered.get(0).id);
        assertEquals(2, recovered.get(0).pageCount);
        assertTrue(saved.file.isFile());
        assertFalse(tombstone.exists());
    }

    @Test
    public void truncatedPdfIsRejectedEvenWithPreviouslyValidMetadata() throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Truncated scan", 2);
        assertEquals(1, new PdfStore(context).list().size());
        try (FileOutputStream output = new FileOutputStream(saved.file)) {
            output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
        }

        assertTrue(new PdfStore(context).list().isEmpty());
        assertTrue(new PdfStore(context).list().isEmpty());
    }

    @Test
    public void sameLengthCorruptionIsRejectedWhenModificationTimeChanges() throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Damaged scan", 2);
        long originalLength = saved.file.length();
        long originalModified = saved.file.lastModified();
        overwriteWithZeros(saved.file);
        assertEquals(originalLength, saved.file.length());
        // Avoid relying on a delay or the filesystem's timestamp precision.
        assertTrue(saved.file.setLastModified(originalModified + 5000));
        assertTrue(saved.file.lastModified() > originalModified);

        assertTrue(new PdfStore(context).list().isEmpty());
    }

    @Test
    public void restartRejectsCorruptionEvenWhenSizeAndModificationTimeMatch() throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Unchanged file facts", 2);
        long originalLength = saved.file.length();
        long originalModified = saved.file.lastModified();
        overwriteWithZeros(saved.file);
        assertTrue(saved.file.setLastModified(originalModified));
        assertEquals(originalLength, saved.file.length());
        assertEquals(originalModified, saved.file.lastModified());

        assertTrue(new PdfStore(context).list().isEmpty());
    }

    @Test
    public void restartRechecksStoredPageCountAndPreservesDisplayName() throws Exception {
        PdfRecord saved = new PdfStore(context).save(source, "My named scan", 2);
        JSONObject index = new JSONObject(new String(new AtomicFile(indexFile()).readFully(),
                StandardCharsets.UTF_8));
        index.getJSONArray("documents").getJSONObject(0).put("pages", 17);
        replaceIndex(index.toString());

        List<PdfRecord> recovered = new PdfStore(context).list();
        assertEquals(1, recovered.size());
        assertEquals(saved.id, recovered.get(0).id);
        assertEquals("My named scan.pdf", recovered.get(0).name);
        assertEquals(2, recovered.get(0).pageCount);
    }

    @Test
    public void brokenIndexRebuildsActualPageCountFromValidPdf() throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Stored scan", 2);
        replaceIndex("{broken metadata");

        List<PdfRecord> rebuilt = new PdfStore(context).list();
        assertEquals(1, rebuilt.size());
        assertEquals(saved.id, rebuilt.get(0).id);
        assertEquals(2, rebuilt.get(0).pageCount);
        assertEquals(saved.file, rebuilt.get(0).file);
        assertEquals(saved.file.length(), rebuilt.get(0).sizeBytes);
        List<PdfRecord> restarted = new PdfStore(context).list();
        assertEquals(1, restarted.size());
        assertEquals(2, restarted.get(0).pageCount);
    }

    @Test
    public void listingRecoversValidPdfEvenWhenIndexRepairCannotBeWritten() throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Still accessible", 2);
        blockIndexWrites();

        List<PdfRecord> recovered = new PdfStore(context).list();
        assertEquals(1, recovered.size());
        assertEquals(saved.id, recovered.get(0).id);
        assertEquals(2, recovered.get(0).pageCount);
        assertEquals(saved.file, recovered.get(0).file);
        assertTrue(saved.file.isFile());
        assertIndexWritesRemainBlocked();
        assertEquals(1, new PdfStore(context).list().size());
    }

    @Test
    public void deletionRemovesPdfEvenWhenIndexCannotBeWritten() throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Free this space", 2);
        blockIndexWrites();

        new PdfStore(context).delete(saved);
        assertFalse(saved.file.exists());
        assertTrue(new PdfStore(context).list().isEmpty());
        assertIndexWritesRemainBlocked();
    }

    @Test
    public void listingRemovesInterruptedSavePartialAndPreservesUnrecognizedFiles()
            throws IOException {
        PdfRecord saved = new PdfStore(context).save(source, "Keep the completed scan", 2);
        File partial = new File(saved.file.getParentFile(), UUID.randomUUID() + ".part");
        try (FileInputStream input = new FileInputStream(saved.file);
             FileOutputStream output = new FileOutputStream(partial)) {
            byte[] prefix = new byte[128];
            int count = input.read(prefix);
            assertTrue(count > 0);
            assertTrue(count < saved.file.length());
            output.write(prefix, 0, count);
            output.getFD().sync();
        }
        File unrelated = new File(saved.file.getParentFile(), "reference.part");
        try (FileOutputStream output = new FileOutputStream(unrelated)) {
            output.write(1);
            output.getFD().sync();
        }

        List<PdfRecord> records = new PdfStore(context).list();
        assertEquals(1, records.size());
        assertEquals(saved.id, records.get(0).id);
        assertEquals("Keep the completed scan.pdf", records.get(0).name);
        assertEquals(2, records.get(0).pageCount);
        assertTrue(saved.file.isFile());
        assertFalse(partial.exists());
        assertTrue(unrelated.isFile());
    }

    private static File stageDeletion(PdfRecord record) {
        File tombstone = new File(record.file.getParentFile(), record.id + ".deleted");
        assertTrue(record.file.renameTo(tombstone));
        assertFalse(record.file.exists());
        assertTrue(tombstone.isFile());
        return tombstone;
    }

    private File indexFile() {
        return new File(new File(context.getFilesDir(), "pdfs"), "index.json");
    }

    /** Models failed metadata writes without filling the device's actual storage. */
    private void blockIndexWrites() throws IOException {
        new AtomicFile(indexFile()).delete();
        for (String suffix : new String[] {"", ".new", ".bak"}) {
            File obstruction = new File(indexFile().getPath() + suffix);
            assertTrue(obstruction.mkdir());
            try (FileOutputStream output = new FileOutputStream(new File(obstruction, "blocker"))) {
                output.write(1);
                output.getFD().sync();
            }
        }
        // Old AtomicFile implementations write the base directly and rename it to
        // .bak first. Newer ones write .new. Nonempty directories block both routes,
        // and prevent backup recovery from replacing or removing the base directory.
        assertIndexWritesRemainBlocked();
    }

    private void assertIndexWritesRemainBlocked() {
        for (String suffix : new String[] {"", ".new", ".bak"}) {
            File obstruction = new File(indexFile().getPath() + suffix);
            assertTrue(obstruction.isDirectory());
            assertTrue(new File(obstruction, "blocker").isFile());
        }
    }

    private void replaceIndex(String contents) throws IOException {
        AtomicFile index = new AtomicFile(indexFile());
        FileOutputStream output = index.startWrite();
        try {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            index.finishWrite(output);
        } catch (IOException error) {
            index.failWrite(output);
            throw error;
        }
    }

    private static void overwriteWithZeros(File file) throws IOException {
        byte[] zeros = new byte[4096];
        long remaining = file.length();
        try (FileOutputStream output = new FileOutputStream(file)) {
            while (remaining > 0) {
                int length = (int) Math.min(zeros.length, remaining);
                output.write(zeros, 0, length);
                remaining -= length;
            }
            output.getFD().sync();
        }
    }

    private static void writeImage(File file, int color) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(color);
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                output.getFD().sync();
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (file.exists()) assertTrue("Cannot remove test file " + file, file.delete());
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
