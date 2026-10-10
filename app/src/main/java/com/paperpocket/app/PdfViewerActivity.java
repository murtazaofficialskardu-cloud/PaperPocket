package com.paperpocket.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A bounded-memory PDF reader. Only the displayed page is held as a bitmap. */
public final class PdfViewerActivity extends Activity {
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_NAME = "name";

    private static final int REQUEST_SAVE_COPY = 510;
    private static final int GREEN = Color.rgb(23, 77, 57);
    private static final int INK = Color.rgb(32, 43, 36);
    private static final int CREAM = Color.rgb(247, 248, 244);
    private static final long MAX_PAGE_PIXELS = 4_000_000L;

    // Oreo's native PDF library can retain a failed parse error for the process lifetime.
    // The manifest isolates this reader from the library screen and PDF validation service.
    private static final AtomicInteger LIVE_VIEWER_WORKERS = new AtomicInteger();
    private static volatile boolean legacyReaderFailed;
    private static volatile boolean retireAfterFinish;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger renderGeneration = new AtomicInteger();
    private volatile boolean destroyed;
    private boolean registeredWorker;

    // The renderer and descriptor are accessed exclusively by the worker.
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private File document;
    private String documentName;
    private int pageCount;
    private int currentPage;
    private int displayedPage = -1;
    private boolean ready;
    private boolean rendering;
    private boolean exporting;
    private PageTransform restoredTransform;

    private ZoomPageView pageView;
    private ProgressBar progress;
    private TextView error;
    private TextView counter;
    private Button previous;
    private Button next;
    private Button more;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LIVE_VIEWER_WORKERS.incrementAndGet();
        registeredWorker = true;
        retireAfterFinish = false;
        getWindow().setStatusBarColor(CREAM);
        getWindow().setNavigationBarColor(CREAM);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (state != null) {
            currentPage = Math.max(0, state.getInt("page", 0));
            if (state.containsKey("zoom")) {
                restoredTransform = new PageTransform(state.getFloat("zoom", 1f),
                        state.getFloat("centerX", .5f), state.getFloat("centerY", .5f));
            }
        }
        String requestedName = getIntent().getStringExtra(EXTRA_NAME);
        documentName = TextUtils.isEmpty(requestedName) ? "Document.pdf" : requestedName;
        buildUi();
        try {
            String path = getIntent().getStringExtra(EXTRA_PATH);
            if (path == null) throw new IOException("Missing document");
            File root = new File(getFilesDir(), "pdfs").getCanonicalFile();
            File candidate = new File(path).getCanonicalFile();
            if (!candidate.getPath().startsWith(root.getPath() + File.separator)
                    || !candidate.isFile() || !candidate.canRead()) {
                throw new IOException("Document is unavailable");
            }
            document = candidate;
            openDocument();
        } catch (Exception problem) {
            showError("This PDF is no longer available. Return to your library and open it again.");
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setMinimumHeight(dp(76));
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        Button back = plainButton("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32);
        back.setContentDescription("Back to PDF library");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.setPadding(dp(8), 0, dp(8), 0);
        toolbar.addView(titleGroup, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView title = text(documentName, 17, INK);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleGroup.addView(title);
        TextView subtitle = text("PDF document", 12, Color.rgb(104, 116, 105));
        subtitle.setPadding(0, dp(3), 0, 0);
        titleGroup.addView(subtitle);

        more = plainButton("⋮");
        more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        more.setContentDescription("Share or save PDF");
        more.setOnClickListener(this::showActions);
        toolbar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));

        FrameLayout preview = new FrameLayout(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        previewParams.setMargins(dp(16), 0, dp(16), 0);
        root.addView(preview, previewParams);
        pageView = new ZoomPageView();
        preview.addView(pageView, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GREEN));
        FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
        preview.addView(progress, spinnerParams);

        error = text("", 16, INK);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(24), dp(24), dp(24), dp(24));
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        preview.addView(error, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setMinimumHeight(dp(64));
        navigation.setPadding(dp(16), dp(12), dp(16), 0);
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        previous = navigationButton("Previous");
        previous.setOnClickListener(v -> changePage(-1));
        navigation.addView(previous, new LinearLayout.LayoutParams(dp(98), -2));
        counter = text("Opening PDF…", 14, INK);
        counter.setGravity(Gravity.CENTER);
        counter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        navigation.addView(counter, new LinearLayout.LayoutParams(0, -2, 1f));
        next = navigationButton("Next");
        next.setOnClickListener(v -> changePage(1));
        navigation.addView(next, new LinearLayout.LayoutParams(dp(98), -2));

        TextView hint = text("Pinch to zoom · drag to move · double-tap to zoom", 11,
                Color.rgb(104, 116, 105));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(12), dp(8), dp(12), dp(16));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        updateControls();
    }

    private void openDocument() {
        worker.execute(() -> {
            boolean nativeOpenAttempted = false;
            try {
                if (destroyed) return;
                descriptor = ParcelFileDescriptor.open(document, ParcelFileDescriptor.MODE_READ_ONLY);
                nativeOpenAttempted = true;
                renderer = new PdfRenderer(descriptor);
                int count = renderer.getPageCount();
                if (count < 1) throw new IOException("PDF contains no pages");
                main.post(() -> {
                    if (destroyed) return;
                    pageCount = count;
                    currentPage = Math.min(currentPage, count - 1);
                    ready = true;
                    pageView.post(this::renderCurrentPage);
                });
            } catch (Exception | OutOfMemoryError problem) {
                if (nativeOpenAttempted) recordLegacyReaderFailure();
                closeRenderer();
                main.post(() -> {
                    if (!destroyed) showError("This PDF could not be opened. It may be damaged or password-protected.");
                });
            }
        });
    }

    private void changePage(int direction) {
        if (!ready || rendering) return;
        int requested = currentPage + direction;
        if (requested < 0 || requested >= pageCount) return;
        currentPage = requested;
        restoredTransform = null;
        renderCurrentPage();
    }

    private void renderCurrentPage() {
        if (destroyed || !ready) return;
        int generation = renderGeneration.incrementAndGet();
        int pageNumber = currentPage;
        int viewWidth = pageView.getWidth();
        if (viewWidth <= 0) viewWidth = getResources().getDisplayMetrics().widthPixels;
        final int requestedWidth = Math.min(2048, Math.max(960, viewWidth * 2));
        final PageTransform transform = restoredTransform;
        rendering = true;
        progress.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        updateControls();
        worker.execute(() -> {
            if (destroyed || generation != renderGeneration.get()) return;
            Bitmap bitmap = null;
            try (PdfRenderer.Page page = renderer.openPage(pageNumber)) {
                if (page.getWidth() <= 0 || page.getHeight() <= 0) {
                    throw new IOException("Invalid PDF page dimensions");
                }
                double factor = requestedWidth / (double) page.getWidth();
                double pixels = page.getWidth() * (double) page.getHeight() * factor * factor;
                if (pixels > MAX_PAGE_PIXELS) factor *= Math.sqrt(MAX_PAGE_PIXELS / pixels);
                factor = Math.min(factor, 8192.0 / Math.max(1, page.getHeight()));
                int width = Math.max(1, (int) Math.round(page.getWidth() * factor));
                int height = Math.max(1, (int) Math.round(page.getHeight() * factor));
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } catch (Exception | OutOfMemoryError problem) {
                recordLegacyReaderFailure();
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                main.post(() -> {
                    if (destroyed || generation != renderGeneration.get()) return;
                    rendering = false;
                    displayedPage = -1;
                    restoredTransform = null;
                    pageView.release();
                    showError("This page could not be displayed. Try another page or open a different PDF.");
                });
                return;
            }
            final Bitmap rendered = bitmap;
            main.post(() -> {
                if (destroyed || generation != renderGeneration.get()) {
                    rendered.recycle();
                    return;
                }
                pageView.setBitmap(rendered, transform);
                displayedPage = pageNumber;
                restoredTransform = null;
                pageView.setContentDescription("PDF page " + (pageNumber + 1) + " of " + pageCount);
                progress.setVisibility(View.GONE);
                rendering = false;
                updateControls();
            });
        });
    }

    private void showActions(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Share PDF").setEnabled(ready);
        menu.getMenu().add(0, 2, 1, exporting ? "Saving copy…" : "Save a copy")
                .setEnabled(ready && !exporting);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) sharePdf();
            if (item.getItemId() == 2) chooseSaveLocation();
            return true;
        });
        menu.show();
    }

    private void sharePdf() {
        if (!ready || document == null) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", document,
                    documentName);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TITLE, documentName);
            send.setClipData(ClipData.newRawUri(documentName, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share PDF"));
        } catch (Exception problem) {
            Toast.makeText(this, "Unable to share this PDF. Please try again.", Toast.LENGTH_LONG).show();
        }
    }

    private void chooseSaveLocation() {
        if (!ready || exporting) return;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/pdf");
        String suggested = documentName.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (suggested.isEmpty()) suggested = "Document.pdf";
        if (!suggested.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) suggested += ".pdf";
        create.putExtra(Intent.EXTRA_TITLE, suggested);
        try {
            startActivityForResult(create, REQUEST_SAVE_COPY);
        } catch (Exception problem) {
            Toast.makeText(this, "No document storage app is available.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE_COPY || resultCode != RESULT_OK
                || data == null || data.getData() == null || document == null || destroyed) return;
        Uri destination = data.getData();
        exporting = true;
        Toast.makeText(this, "Saving PDF copy…", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            boolean succeeded = false;
            try (FileInputStream input = new FileInputStream(document);
                 OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("Storage is unavailable");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();
                succeeded = true;
            } catch (Exception problem) {
                // The library original is never changed by an export failure.
                succeeded = false;
            }
            final boolean saved = succeeded;
            main.post(() -> {
                if (destroyed) return;
                exporting = false;
                Toast.makeText(this, saved ? "PDF copy saved." : "Unable to save the copy. Please try again.",
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void updateControls() {
        previous.setEnabled(ready && !rendering && currentPage > 0);
        next.setEnabled(ready && !rendering && currentPage < pageCount - 1);
        previous.setAlpha(previous.isEnabled() ? 1f : .4f);
        next.setAlpha(next.isEnabled() ? 1f : .4f);
        more.setEnabled(ready);
        more.setAlpha(ready ? 1f : .4f);
        if (ready) counter.setText((currentPage + 1) + " / " + pageCount);
    }

    private void showError(String message) {
        progress.setVisibility(View.GONE);
        error.setText(message);
        error.setVisibility(View.VISIBLE);
        if (!ready) counter.setText("PDF unavailable");
        updateControls();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", currentPage);
        PageTransform transform = displayedPage == currentPage ? pageView.captureTransform() : null;
        if (transform == null) transform = restoredTransform;
        if (transform != null) {
            state.putFloat("zoom", transform.zoom);
            state.putFloat("centerX", transform.centerX);
            state.putFloat("centerY", transform.centerY);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        renderGeneration.incrementAndGet();
        if (pageView != null) pageView.release();
        retireAfterFinish = isFinishing() && !isChangingConfigurations();
        final boolean releaseWorker = registeredWorker;
        registeredWorker = false;
        // This runs after any queued save-copy task, so process retirement cannot truncate it.
        worker.execute(() -> {
            try {
                closeRenderer();
            } finally {
                if (releaseWorker) LIVE_VIEWER_WORKERS.decrementAndGet();
                main.post(this::retireFailedLegacyViewerProcess);
            }
        });
        worker.shutdown();
        super.onDestroy();
    }

    private static boolean hasLegacyPdfLibrary() {
        return Build.VERSION.SDK_INT == 26 || Build.VERSION.SDK_INT == 27;
    }

    private static void recordLegacyReaderFailure() {
        if (hasLegacyPdfLibrary()) legacyReaderFailed = true;
    }

    private void retireFailedLegacyViewerProcess() {
        if (!hasLegacyPdfLibrary() || !legacyReaderFailed || !retireAfterFinish
                || LIVE_VIEWER_WORKERS.get() != 0) return;
        // Fail closed if the manifest or process lookup differs: never terminate the main app.
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return;
        try {
            java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return;
            int ownPid = Process.myPid();
            String expectedProcess = getPackageName() + ":pdf_viewer";
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pid == ownPid && expectedProcess.equals(process.processName)) {
                    Process.killProcess(ownPid);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // A denied process lookup leaves the activity safely closed without killing anything.
        }
    }

    private void closeRenderer() {
        if (renderer != null) {
            try { renderer.close(); } catch (Exception ignored) { recordLegacyReaderFailure(); }
            renderer = null;
        }
        if (descriptor != null) {
            try { descriptor.close(); } catch (IOException ignored) { }
            descriptor = null;
        }
    }

    private int dp(float amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button plainButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(GREEN);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        return button;
    }

    private Button navigationButton(String label) {
        Button button = plainButton(label);
        button.setTextSize(13);
        button.setMinHeight(dp(48));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(225, 235, 220));
        background.setCornerRadius(dp(14));
        button.setBackground(background);
        return button;
    }

    private static final class PageTransform {
        final float zoom;
        final float centerX;
        final float centerY;

        PageTransform(float zoom, float centerX, float centerY) {
            this.zoom = zoom;
            this.centerX = centerX;
            this.centerY = centerY;
        }
    }

    private final class ZoomPageView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF destination = new RectF();
        private final ScaleGestureDetector pinch;
        private final GestureDetector gestures;
        private Bitmap bitmap;
        private float baseScale = 1f;
        private float scale = 1f;
        private float offsetX;
        private float offsetY;

        ZoomPageView() {
            super(PdfViewerActivity.this);
            setBackgroundColor(Color.rgb(232, 237, 228));
            setFocusable(true);
            setClickable(true);
            pinch = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    if (bitmap == null) return false;
                    zoomAround(detector.getFocusX(), detector.getFocusY(), scale * detector.getScaleFactor());
                    return true;
                }
            });
            gestures = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }

                @Override
                public boolean onScroll(MotionEvent start, MotionEvent current, float dx, float dy) {
                    if (bitmap == null || pinch.isInProgress()) return false;
                    offsetX -= dx;
                    offsetY -= dy;
                    clampOffsets();
                    invalidate();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent event) {
                    if (bitmap == null) return false;
                    zoomAround(event.getX(), event.getY(), scale > baseScale * 1.1f ? baseScale : baseScale * 2.5f);
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent event) {
                    return performClick();
                }
            });
        }

        void setBitmap(Bitmap nextBitmap, PageTransform transform) {
            Bitmap old = bitmap;
            bitmap = nextBitmap;
            baseScale = Math.max(1, getWidth()) / (float) bitmap.getWidth();
            scale = baseScale;
            offsetX = 0;
            offsetY = bitmap.getHeight() * scale < getHeight()
                    ? (getHeight() - bitmap.getHeight() * scale) / 2f : 0;
            if (transform != null) applyTransform(transform);
            clampOffsets();
            invalidate();
            if (old != null && old != nextBitmap && !old.isRecycled()) old.recycle();
        }

        PageTransform captureTransform() {
            if (bitmap == null || scale <= 0) return null;
            return new PageTransform(scale / baseScale,
                    (getWidth() / 2f - offsetX) / (bitmap.getWidth() * scale),
                    (getHeight() / 2f - offsetY) / (bitmap.getHeight() * scale));
        }

        private void applyTransform(PageTransform transform) {
            scale = Math.max(baseScale, Math.min(baseScale * 5f, baseScale * transform.zoom));
            offsetX = getWidth() / 2f - transform.centerX * bitmap.getWidth() * scale;
            offsetY = getHeight() / 2f - transform.centerY * bitmap.getHeight() * scale;
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            PageTransform transform = null;
            if (bitmap != null && oldWidth > 0 && oldHeight > 0) {
                transform = new PageTransform(scale / baseScale,
                        (oldWidth / 2f - offsetX) / (bitmap.getWidth() * scale),
                        (oldHeight / 2f - offsetY) / (bitmap.getHeight() * scale));
            }
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (bitmap != null) {
                baseScale = Math.max(1, width) / (float) bitmap.getWidth();
                if (transform != null) applyTransform(transform);
                else scale = baseScale;
                clampOffsets();
            }
        }

        private void zoomAround(float x, float y, float requestedScale) {
            float nextScale = Math.max(baseScale, Math.min(baseScale * 5f, requestedScale));
            float ratio = nextScale / scale;
            offsetX = x - (x - offsetX) * ratio;
            offsetY = y - (y - offsetY) * ratio;
            scale = nextScale;
            clampOffsets();
            invalidate();
        }

        private void clampOffsets() {
            if (bitmap == null) return;
            float imageWidth = bitmap.getWidth() * scale;
            float imageHeight = bitmap.getHeight() * scale;
            offsetX = imageWidth <= getWidth() ? (getWidth() - imageWidth) / 2f
                    : Math.max(getWidth() - imageWidth, Math.min(0, offsetX));
            offsetY = imageHeight <= getHeight() ? (getHeight() - imageHeight) / 2f
                    : Math.max(getHeight() - imageHeight, Math.min(0, offsetY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null || bitmap.isRecycled()) return;
            destination.set(offsetX, offsetY, offsetX + bitmap.getWidth() * scale,
                    offsetY + bitmap.getHeight() * scale);
            canvas.drawBitmap(bitmap, null, destination, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            gestures.onTouchEvent(event);
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        void release() {
            Bitmap old = bitmap;
            bitmap = null;
            invalidate();
            if (old != null && !old.isRecycled()) old.recycle();
        }
    }
}
