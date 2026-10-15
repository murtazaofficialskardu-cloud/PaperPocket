package com.paperpocket.app;

import android.content.Context;
import android.content.Intent;
import android.system.ErrnoException;
import android.system.Os;
import android.util.AtomicFile;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One explicit, durable image-to-PDF job. No reboot, force-stop or automatic retry. */
public final class BackgroundPdfJobs {
    /** Draft writers and completion handling use the same lock, always before JOB_LOCK. */
    public static final Object DRAFT_LOCK = new Object();
    private static final Object JOB_LOCK = new Object();
    private static final String PROCESS = UUID.randomUUID().toString();
    private static final int MAX_STATUS_BYTES = 1024 * 1024;
    private static final long MAX_SOURCE_BYTES = 80L * 1024 * 1024;
    private static Runner runner;
    /** A full disk must not leave an in-process conversion appearing active forever. */
    private static JSONObject terminalStatusInMemory;

    private BackgroundPdfJobs() { }

    public static final class Status {
        public final String id, state, pdfId, name, error, sourceFingerprint;
        public final int done, total;
        public final long createdAt, updatedAt;
        public final boolean active, acknowledged;

        private Status(JSONObject value) throws JSONException {
            id = value.getString("id");
            state = value.getString("state");
            pdfId = value.optString("pdfId", "");
            name = value.optString("name", "Document");
            error = value.optString("error", "");
            sourceFingerprint = value.optString("sourceFingerprint", "");
            done = value.optInt("done", 0);
            total = value.optInt("total", 1);
            createdAt = value.optLong("createdAt", 0);
            updatedAt = value.optLong("updatedAt", createdAt);
            active = activeState(state);
            acknowledged = value.optBoolean("acknowledged", false);
        }
    }

    /** Called by a visible activity. Source links take no second copy of the image bytes. */
    public static String start(Context context, List<PageItem> pages,
                               PdfConverter.Options options) throws IOException {
        Context app = application(context);
        String id = UUID.randomUUID().toString();
        synchronized (DRAFT_LOCK) {
            synchronized (JOB_LOCK) {
                JSONObject previous = recover(app, readJson(app));
                if ((previous != null && activeState(previous.optString("state"))) || runner != null) {
                    throw new IOException("A PDF is already being created. Wait or cancel it first.");
                }
                if (pages == null || pages.isEmpty() || pages.size() > 100) {
                    throw new IOException("Choose between 1 and 100 images to create a PDF.");
                }
                // A process can die between linking an image and publishing status.json.
                // There is no active job here, so unreferenced private snapshots are disposable.
                File[] stale = root(app).listFiles();
                if (stale != null) for (File entry : stale) {
                    if (entry.isDirectory() && validId(entry.getName())) deleteTree(entry);
                }
                File directory = jobDirectory(app, id);
                if (!directory.mkdirs()) throw new IOException("Unable to prepare this PDF. Your draft is kept.");
                boolean published = false;
                try {
                    JSONArray snapshots = new JSONArray();
                    for (int index = 0; index < pages.size(); index++) {
                        PageItem page = pages.get(index);
                        File source = checkedSource(app, page);
                        File snapshot = new File(directory, index + ".img");
                        try { Os.link(source.getAbsolutePath(), snapshot.getAbsolutePath()); }
                        catch (ErrnoException error) {
                            throw new IOException("This device could not prepare background conversion. Your draft is kept.", error);
                        }
                        snapshots.put(new JSONObject().put("id", page.id)
                                .put("file", snapshot.getName()).put("rotation", page.rotation)
                                .put("filter", page.filter.name()));
                    }
                    PdfConverter.Options selected = options == null ? new PdfConverter.Options() : options;
                    if (selected.idCardMode && pages.size() != 2) {
                        throw new IOException("ID-card mode needs exactly two images: front and back.");
                    }
                    long now = System.currentTimeMillis();
                    JSONObject value = new JSONObject().put("id", id).put("owner", PROCESS)
                            .put("state", "preparing").put("done", 0)
                            .put("total", pages.size())
                            .put("createdAt", now).put("updatedAt", now)
                            .put("name", PdfGeometry.normalizeName(selected.name))
                            .put("sourceFingerprint", fingerprint(pages))
                            .put("pages", snapshots).put("options", encodeOptions(selected))
                            .put("acknowledged", false);
                    writeJson(app, value);
                    published = true;
                    try {
                        ContextCompat.startForegroundService(app,
                                new Intent(app, BackgroundPdfService.class)
                                        .setAction(BackgroundPdfService.ACTION_START)
                                        .putExtra(BackgroundPdfService.EXTRA_JOB_ID, id));
                    } catch (RuntimeException error) {
                        terminal(app, value, "failed", "Android could not start background conversion. Keep the app open and try again.", "");
                        throw new IOException("Background conversion could not start. Your draft is kept.", error);
                    }
                    if (previous != null) deleteSnapshots(app, previous.optString("id"));
                    return id;
                } catch (JSONException error) {
                    throw new IOException("Unable to prepare this PDF. Your draft is kept.", error);
                } finally {
                    if (!published) deleteTree(directory);
                }
            }
        }
    }

    public static Status read(Context context) throws IOException {
        synchronized (JOB_LOCK) {
            JSONObject value = recover(application(context), readJson(application(context)));
            if (value == null) return null;
            try { return new Status(value); }
            catch (JSONException error) { throw new IOException("The conversion status could not be read.", error); }
        }
    }

    public static boolean isActive(Context context) throws IOException {
        Status value = read(context);
        return value != null && value.active;
    }

    /** Cancellation stops before the durable library-save barrier; saving finishes atomically. */
    public static void cancel(Context context, String id) throws IOException {
        synchronized (JOB_LOCK) {
            Context app = application(context);
            JSONObject value = recover(app, readJson(app));
            if (!matches(value, id) || !activeState(value.optString("state"))) return;
            if ("saving".equals(value.optString("state"))) return;
            if (runner != null && runner.id.equals(id)) {
                runner.cancelled = true;
                set(value, "state", "cancelling");
                stamp(value);
                writeJson(app, value);
                if (runner.thread != null) runner.thread.interrupt();
            } else {
                terminal(app, value, "cancelled", "", "");
            }
        }
    }

    /** Acknowledgment is durable so an activity restart does not redisplay an old result. */
    public static void acknowledge(Context context, String id) throws IOException {
        synchronized (JOB_LOCK) {
            Context app = application(context);
            JSONObject value = recover(app, readJson(app));
            if (!matches(value, id) || activeState(value.optString("state"))) return;
            set(value, "acknowledged", true);
            writeJson(app, value);
        }
    }

    /** Exact ordered page IDs, paths and reversible edits identify the draft used by a job. */
    public static String fingerprint(List<PageItem> pages) {
        try {
            JSONArray values = new JSONArray();
            if (pages != null) for (PageItem page : pages) {
                if (page == null) throw new IllegalArgumentException("A draft page is missing.");
                values.put(new JSONObject().put("id", page.id).put("path", page.path)
                        .put("rotation", page.rotation)
                        .put("filter", page.filter == null ? "" : page.filter.name()));
            }
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(values.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : hash) result.append(String.format(java.util.Locale.US, "%02x", item & 255));
            return result.toString();
        } catch (JSONException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Unable to identify the PDF draft.", impossible);
        }
    }

    static final class Runner {
        final String id;
        final List<PageItem> pages;
        final PdfConverter.Options options;
        volatile Thread thread;
        volatile boolean cancelled;

        Runner(String id, List<PageItem> pages, PdfConverter.Options options) {
            this.id = id;
            this.pages = pages;
            this.options = options;
        }
    }

    static Runner claim(Context context, String id) throws IOException {
        synchronized (JOB_LOCK) {
            Context app = application(context);
            JSONObject value = recover(app, readJson(app));
            if (!matches(value, id) || !"preparing".equals(value.optString("state")) || runner != null) return null;
            try {
                File directory = jobDirectory(app, id);
                JSONArray entries = value.getJSONArray("pages");
                if (entries.length() < 1 || entries.length() > 100) throw new IOException("Invalid PDF snapshot.");
                List<PageItem> pages = new ArrayList<>();
                for (int index = 0; index < entries.length(); index++) {
                    JSONObject item = entries.getJSONObject(index);
                    String filename = index + ".img";
                    if (!filename.equals(item.getString("file"))) throw new IOException("Invalid PDF snapshot.");
                    File file = new File(directory, filename);
                    if (!file.isFile() || file.length() < 1 || file.length() > MAX_SOURCE_BYTES) {
                        throw new IOException("A background image is missing. Your draft is kept.");
                    }
                    PageItem page = new PageItem(file.getAbsolutePath());
                    page.id = item.getString("id");
                    page.rotation = item.getInt("rotation");
                    page.filter = PageItem.Filter.valueOf(item.getString("filter"));
                    pages.add(page);
                }
                PdfConverter.Options selected = decodeOptions(value.getJSONObject("options"));
                selected.name = value.getString("name");
                Runner claimed = new Runner(id, pages, selected);
                set(value, "state", "running");
                stamp(value);
                writeJson(app, value);
                runner = claimed;
                return claimed;
            } catch (JSONException | IllegalArgumentException error) {
                terminal(app, value, "failed", "The background images could not be restored. Your draft is kept.", "");
                throw new IOException("Invalid background PDF job.", error);
            }
        }
    }

    static void progress(Context context, Runner current, int done, int total) throws IOException {
        synchronized (JOB_LOCK) {
            check(current);
            JSONObject value = readJson(application(context));
            if (!matches(value, current.id)) throw new InterruptedIOException("PDF creation was cancelled.");
            set(value, "done", Math.max(0, Math.min(done, total)));
            set(value, "total", Math.max(1, total));
            stamp(value);
            writeJson(application(context), value);
        }
    }

    /** The lock decides cancellation versus saving once; no interrupt is issued after this point. */
    static PdfRecord save(Context context, Runner current, File pdf, Runnable saving) throws IOException {
        synchronized (JOB_LOCK) {
            check(current);
            JSONObject value = readJson(application(context));
            if (!matches(value, current.id)) throw new InterruptedIOException("PDF creation was cancelled.");
            set(value, "state", "saving");
            stamp(value);
            writeJson(application(context), value);
        }
        if (saving != null) saving.run();
        return new PdfStore(context).save(pdf, current.options.name,
                current.options.idCardMode ? 1 : current.pages.size());
    }

    static void finish(Context context, Runner current, PdfRecord saved, String error) throws IOException {
        synchronized (JOB_LOCK) {
            JSONObject value = readJson(application(context));
            try {
                if (matches(value, current.id)) {
                    String state = saved != null ? "completed" : current.cancelled ? "cancelled" : "failed";
                    terminal(application(context), value, state, saved != null ? "" : error,
                            saved == null ? "" : saved.id);
                }
            } finally {
                if (runner == current) runner = null;
                deleteSnapshots(application(context), current.id);
            }
        }
    }

    static void failUnclaimed(Context context, String id, String error) {
        synchronized (JOB_LOCK) {
            try {
                JSONObject value = readJson(application(context));
                if (matches(value, id) && activeState(value.optString("state")) && runner == null) {
                    terminal(application(context), value, "failed", error, "");
                }
            } catch (IOException ignored) { /* A fresh process marks this interrupted if storage is unavailable. */ }
        }
    }

    static void check(Runner current) throws InterruptedIOException {
        if (current.cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("PDF creation was cancelled.");
        }
    }

    private static JSONObject recover(Context app, JSONObject value) throws IOException {
        if (value != null && activeState(value.optString("state"))
                && !PROCESS.equals(value.optString("owner"))) {
            terminal(app, value, "interrupted", "Android stopped the previous conversion. Your draft is kept; create the PDF again.", "");
        }
        return value;
    }

    private static void terminal(Context app, JSONObject value, String state, String error,
                                 String pdfId) throws IOException {
        set(value, "state", state);
        set(value, "error", error == null ? "" : error);
        set(value, "pdfId", pdfId);
        if ("completed".equals(state)) set(value, "done", value.optInt("total", 1));
        stamp(value);
        try { writeJson(app, value); }
        catch (IOException failure) { terminalStatusInMemory = value; throw failure; }
        deleteSnapshots(app, value.optString("id"));
    }

    private static File checkedSource(Context app, PageItem page) throws IOException {
        if (page == null || page.path == null || page.path.isEmpty()
                || page.id == null || page.id.isEmpty() || page.id.length() > 200
                || page.filter == null || page.rotation < 0 || page.rotation > 270 || page.rotation % 90 != 0) {
            throw new IOException("A selected image could not be read. Your draft is kept.");
        }
        File source = new File(page.path);
        File draft = new File(app.getFilesDir(), "draft-pages").getCanonicalFile();
        // Canonical equality excludes symlink aliases and paths outside private immutable draft files.
        if (!source.getAbsoluteFile().equals(source.getCanonicalFile())
                || !source.getCanonicalPath().startsWith(draft.getPath() + File.separator)
                || !source.isFile() || source.length() < 1 || source.length() > MAX_SOURCE_BYTES) {
            throw new IOException("A selected image could not be read. Your draft is kept.");
        }
        return source;
    }

    private static JSONObject encodeOptions(PdfConverter.Options selected) throws JSONException {
        return new JSONObject().put("profile", (selected.profile == null ? PdfConverter.Profile.BALANCED : selected.profile).name())
                .put("dpi", selected.dpi).put("jpegQuality", selected.jpegQuality)
                .put("paper", selected.paper).put("margin", selected.margin)
                .put("idCardMode", selected.idCardMode);
    }

    private static PdfConverter.Options decodeOptions(JSONObject value) throws JSONException {
        PdfConverter.Options options = new PdfConverter.Options();
        options.profile = PdfConverter.Profile.valueOf(value.getString("profile"));
        options.dpi = value.getInt("dpi");
        options.jpegQuality = value.getInt("jpegQuality");
        options.paper = value.getString("paper");
        options.margin = value.getBoolean("margin");
        options.idCardMode = value.optBoolean("idCardMode", false);
        return options;
    }

    private static boolean activeState(String state) {
        return "preparing".equals(state) || "running".equals(state)
                || "saving".equals(state) || "cancelling".equals(state);
    }

    private static boolean matches(JSONObject value, String id) {
        return value != null && id != null && id.equals(value.optString("id"));
    }

    private static File root(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "background_pdf");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Conversion status storage is unavailable.");
        return root;
    }

    private static File jobDirectory(Context context, String id) throws IOException {
        if (!validId(id)) {
            throw new IOException("Invalid conversion identifier.");
        }
        return new File(root(context), id);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static JSONObject readJson(Context context) throws IOException {
        if (terminalStatusInMemory != null) return terminalStatusInMemory;
        AtomicFile file = new AtomicFile(new File(root(context), "status.json"));
        if (!file.getBaseFile().isFile() && !new File(file.getBaseFile().getPath() + ".bak").isFile()) return null;
        if (file.getBaseFile().length() > MAX_STATUS_BYTES
                || new File(file.getBaseFile().getPath() + ".bak").length() > MAX_STATUS_BYTES) {
            throw new IOException("Conversion status is too large.");
        }
        byte[] bytes = file.readFully();
        if (bytes.length > MAX_STATUS_BYTES) throw new IOException("Conversion status is too large.");
        try {
            JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            jobDirectory(context, value.getString("id"));
            return value;
        } catch (JSONException error) { throw new IOException("Conversion status could not be read.", error); }
    }

    private static void writeJson(Context context, JSONObject value) throws IOException {
        AtomicFile target = new AtomicFile(new File(root(context), "status.json"));
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STATUS_BYTES) throw new IOException("Conversion status is too large.");
        FileOutputStream output = null;
        try {
            output = target.startWrite();
            output.write(bytes);
            output.getFD().sync();
            target.finishWrite(output);
            output = null;
            terminalStatusInMemory = null;
        } finally { if (output != null) target.failWrite(output); }
    }

    private static void stamp(JSONObject value) { set(value, "updatedAt", System.currentTimeMillis()); }

    private static void set(JSONObject value, String key, Object item) {
        try { value.put(key, item); }
        catch (JSONException impossible) { throw new IllegalStateException("Invalid conversion status.", impossible); }
    }

    private static void deleteSnapshots(Context context, String id) {
        try { deleteTree(jobDirectory(context, id)); }
        catch (IOException ignored) { /* Only validated private job IDs are ever removed. */ }
    }

    private static void deleteTree(File file) {
        File[] entries = file.listFiles();
        if (entries != null) for (File entry : entries) entry.delete();
        file.delete();
    }

    private static Context application(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
