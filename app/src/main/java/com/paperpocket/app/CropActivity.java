package com.paperpocket.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Four-corner, perspective-corrected document crop. All inputs and outputs stay private. */
public final class CropActivity extends Activity {
    private static final int PAPER = Color.rgb(247, 248, 244);
    private static final int INK = Color.rgb(32, 43, 36);
    private static final int GREEN = Color.rgb(23, 77, 57);
    private static final String STATE_CORNERS = "crop_corners";
    private static final int MAX_OUTPUT_SIDE = 3508;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private volatile boolean canceled;
    private File sourceFile;
    private CropView cropView;
    private Button applyButton;
    private Button resetButton;
    private TextView status;
    private ProgressBar progress;
    private Bitmap preview;
    private boolean saving;
    private boolean returningCrop;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(Build.VERSION.SDK_INT>=33){
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::cancelCrop);
        }
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildUi();
        try {
            sourceFile = validateSource(getIntent().getStringExtra("path"));
        } catch (IOException | SecurityException e) {
            status.setText("This image is unavailable. Return and import it again.");
            progress.setVisibility(View.GONE);
            return;
        }
        final float[] restored = savedInstanceState == null ? null
                : savedInstanceState.getFloatArray(STATE_CORNERS);
        worker.execute(() -> {
            Bitmap decoded = null;
            try {
                decoded = ImageLoader.load(new PageItem(sourceFile.getAbsolutePath()), 1800);
                if (decoded == null) throw new IOException("Image could not be decoded");
                final Bitmap ready = decoded;
                decoded = null;
                runOnUiThread(() -> {
                    if (destroyed) { ready.recycle(); return; }
                    preview = ready;
                    cropView.setImage(ready, restored);
                    progress.setVisibility(View.GONE);
                    applyButton.setEnabled(true);
                    resetButton.setEnabled(true);
                    status.setText("Drag the four corners to the document edges.");
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (decoded != null) decoded.recycle();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    progress.setVisibility(View.GONE);
                    status.setText("Could not open this image. Try a smaller image or import it again.");
                });
            }
        });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(16), dp(8));
        Button back = button("Back", false);
        back.setContentDescription("Cancel document crop and go back");
        back.setOnClickListener(v -> cancelCrop());
        header.addView(back, new LinearLayout.LayoutParams(dp(80), dp(48)));
        TextView title = new TextView(this);
        title.setText("Adjust document");
        title.setTextColor(INK);
        title.setTextSize(21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        status = new TextView(this);
        status.setText("Opening image…");
        status.setTextColor(INK);
        status.setTextSize(14);
        status.setPadding(dp(24), dp(4), dp(24), dp(12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        progress = new ProgressBar(this);
        LinearLayout.LayoutParams spinner = new LinearLayout.LayoutParams(dp(32), dp(32));
        spinner.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progress, spinner);

        cropView = new CropView();
        root.addView(cropView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView hint = new TextView(this);
        hint.setText("The selected area will be straightened into a flat page.");
        hint.setTextColor(Color.rgb(94, 108, 99));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(20), dp(12), dp(20), dp(4));
        root.addView(hint);

        LinearLayout footer = new LinearLayout(this);
        footer.setPadding(dp(20), dp(12), dp(20), dp(20));
        resetButton = button("Reset", false);
        resetButton.setEnabled(false);
        resetButton.setContentDescription("Reset crop to the full image");
        resetButton.setOnClickListener(v -> cropView.reset());
        footer.addView(resetButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        View space = new View(this);
        footer.addView(space, new LinearLayout.LayoutParams(dp(12), 1));
        applyButton = button("Apply crop", true);
        applyButton.setEnabled(false);
        applyButton.setOnClickListener(v -> applyCrop());
        footer.addView(applyButton, new LinearLayout.LayoutParams(0, dp(52), 1.6f));
        root.addView(footer);
        setContentView(root);
        root.requestApplyInsets();
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : GREEN);
        button.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        background.setColor(primary ? GREEN : Color.rgb(229, 236, 227));
        button.setBackground(background);
        return button;
    }

    private File validateSource(String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("Missing image path");
        File file = new File(path).getCanonicalFile();
        File drafts = new File(getFilesDir(), "draft-pages").getCanonicalFile();
        File cache = getCacheDir().getCanonicalFile();
        String canonical = file.getPath();
        boolean privatePath = canonical.startsWith(drafts.getPath() + File.separator)
                || canonical.startsWith(cache.getPath() + File.separator);
        if (!privatePath || !file.isFile() || !file.canRead()) {
            throw new IOException("Not a private image file");
        }
        return file;
    }

    private void applyCrop() {
        if (saving || preview == null) return;
        final float[] corners = cropView.getCorners();
        if (!validQuad(corners)) {
            Toast.makeText(this, "Keep the corners in order and select a larger document area.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        saving = true;
        cropView.setEditingEnabled(false);
        applyButton.setEnabled(false);
        resetButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Straightening your page at high quality…");
        worker.execute(() -> {
            Bitmap source = null;
            Bitmap output = null;
            File destination = null;
            try {
                // Load again at export resolution; preview pixels are never used for the saved crop.
                source = ImageLoader.load(new PageItem(sourceFile.getAbsolutePath()), MAX_OUTPUT_SIDE);
                if (source == null) throw new IOException("Image could not be decoded");
                if (destroyed || canceled) return;
                float[] sourcePoints = new float[8];
                for (int i = 0; i < 4; i++) {
                    sourcePoints[i * 2] = corners[i * 2] * source.getWidth();
                    sourcePoints[i * 2 + 1] = corners[i * 2 + 1] * source.getHeight();
                }
                float width = Math.max(distance(sourcePoints, 0, 1), distance(sourcePoints, 3, 2));
                float height = Math.max(distance(sourcePoints, 0, 3), distance(sourcePoints, 1, 2));
                float scale = Math.min(1f, MAX_OUTPUT_SIDE / Math.max(width, height));
                int outputWidth = Math.max(2, Math.round(width * scale));
                int outputHeight = Math.max(2, Math.round(height * scale));
                float[] rectangle = {0, 0, outputWidth, 0, outputWidth, outputHeight, 0, outputHeight};
                Matrix transform = new Matrix();
                if (!transform.setPolyToPoly(sourcePoints, 0, rectangle, 0, 4)) {
                    throw new IOException("Invalid document shape");
                }
                output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(output);
                canvas.drawColor(Color.WHITE);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                canvas.drawBitmap(source, transform, paint);
                File directory = new File(getFilesDir(), "draft-pages");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Could not create private image directory");
                }
                destination = new File(directory, "crop-" + UUID.randomUUID() + ".jpg");
                try (FileOutputStream stream = new FileOutputStream(destination)) {
                    if (!output.compress(Bitmap.CompressFormat.JPEG, 98, stream)) {
                        throw new IOException("Could not save image");
                    }
                    stream.flush();
                }
                if (destroyed || canceled) { destination.delete(); return; }
                final File resultFile = destination;
                destination = null;
                runOnUiThread(() -> {
                    if (destroyed || canceled) { resultFile.delete(); return; }
                    returningCrop = true;
                    setResult(RESULT_OK, new Intent().putExtra("path", resultFile.getAbsolutePath()));
                    finish();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (destination != null) destination.delete();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    saving = false;
                    cropView.setEditingEnabled(true);
                    applyButton.setEnabled(true);
                    resetButton.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    status.setText("Could not save the crop. Close other apps and try again.");
                });
            } finally {
                if (output != null) output.recycle();
                if (source != null) source.recycle();
            }
        });
    }

    private static float distance(float[] points, int a, int b) {
        float dx = points[a * 2] - points[b * 2];
        float dy = points[a * 2 + 1] - points[b * 2 + 1];
        return (float) Math.hypot(dx, dy);
    }

    private void cancelCrop() {
        canceled = true;
        setResult(RESULT_CANCELED);
        finish();
    }

    // API 33+ uses the native OnBackInvokedCallback registered in onCreate.
    // This override preserves hardware/legacy back behavior on API 26–32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        cancelCrop();
    }

    @Override public void finish() {
        // Also cover framework/predictive-back finish paths that bypass onBackPressed().
        if (!returningCrop) canceled = true;
        super.finish();
    }

    /** Ordered clockwise, convex, finite and large enough to avoid unstable perspective matrices. */
    private static boolean validQuad(float[] points) {
        if (points == null || points.length != 8) return false;
        for (float point : points) {
            if (Float.isNaN(point) || Float.isInfinite(point) || point < 0f || point > 1f) return false;
        }
        float areaTwice = 0f;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            int k = (i + 2) % 4;
            float ax = points[j * 2] - points[i * 2];
            float ay = points[j * 2 + 1] - points[i * 2 + 1];
            float bx = points[k * 2] - points[j * 2];
            float by = points[k * 2 + 1] - points[j * 2 + 1];
            if (ax * by - ay * bx < 0.0008f || distance(points, i, j) < 0.03f) return false;
            areaTwice += points[i * 2] * points[j * 2 + 1] - points[j * 2] * points[i * 2 + 1];
        }
        return areaTwice >= 0.015f;
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cropView != null) outState.putFloatArray(STATE_CORNERS, cropView.getCorners());
    }

    @Override protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        if (preview != null) {
            cropView.setImage(null, null);
            preview.recycle();
            preview = null;
        }
        super.onDestroy();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class CropView extends ViewGroup {
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF imageRect = new RectF();
        private final float[] corners = {.03f, .03f, .97f, .03f, .97f, .97f, .03f, .97f};
        private final CornerHandle[] handles = new CornerHandle[4];
        private Bitmap image;
        private int activeCorner = -1;
        private float dragOffsetX;
        private float dragOffsetY;
        private boolean editingEnabled = true;

        CropView() {
            super(CropActivity.this);
            setWillNotDraw(false);
            setClipChildren(false);
            setBackgroundColor(Color.rgb(225, 232, 223));
            setContentDescription("Document crop preview. Move the four numbered corner handles to the document edges.");
            outlinePaint.setColor(Color.WHITE);
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(dp(2));
            gridPaint.setColor(Color.argb(150, 255, 255, 255));
            gridPaint.setStrokeWidth(dp(1));
            shadePaint.setColor(Color.argb(130, 10, 28, 19));
            String[] names = {"Top left", "Top right", "Bottom right", "Bottom left"};
            for (int i = 0; i < 4; i++) {
                handles[i] = new CornerHandle(i);
                handles[i].setContentDescription(names[i] + " crop corner, handle " + (i + 1)
                        + ". Drag to move. Use arrow keys to adjust.");
                handles[i].setFocusable(true);
                handles[i].setClickable(true);
                handles[i].setVisibility(INVISIBLE);
                addView(handles[i], new LayoutParams(dp(48), dp(48)));
            }
        }

        void setImage(Bitmap bitmap, float[] restored) {
            image = bitmap;
            if (restored != null && validQuad(restored)) System.arraycopy(restored, 0, corners, 0, 8);
            for (CornerHandle handle : handles) handle.setVisibility(bitmap == null ? INVISIBLE : VISIBLE);
            requestLayout();
            invalidate();
        }

        void reset() {
            float[] full = {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};
            System.arraycopy(full, 0, corners, 0, 8);
            requestLayout();
            invalidate();
            announceForAccessibility("Crop reset to full image");
        }

        float[] getCorners() { return corners.clone(); }

        void setEditingEnabled(boolean enabled) {
            editingEnabled = enabled;
            for (CornerHandle handle : handles) handle.setEnabled(enabled);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
            int handleSpec = MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY);
            for (CornerHandle handle : handles) handle.measure(handleSpec, handleSpec);
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            fitImage();
            for (int i = 0; i < handles.length; i++) {
                int x = Math.round(screenX(i)) - dp(24);
                int y = Math.round(screenY(i)) - dp(24);
                handles[i].layout(x, y, x + dp(48), y + dp(48));
            }
        }

        private void fitImage() {
            if (image == null) { imageRect.setEmpty(); return; }
            float margin = dp(28);
            float availableWidth = Math.max(1f, getWidth() - margin * 2);
            float availableHeight = Math.max(1f, getHeight() - margin * 2);
            float scale = Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight());
            float width = image.getWidth() * scale;
            float height = image.getHeight() * scale;
            float left = (getWidth() - width) / 2;
            float top = (getHeight() - height) / 2;
            imageRect.set(left, top, left + width, top + height);
        }

        private float screenX(int corner) { return imageRect.left + corners[corner * 2] * imageRect.width(); }
        private float screenY(int corner) { return imageRect.top + corners[corner * 2 + 1] * imageRect.height(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (image == null || image.isRecycled() || imageRect.isEmpty()) return;
            canvas.drawBitmap(image, null, imageRect, imagePaint);
            Path document = new Path();
            document.moveTo(screenX(0), screenY(0));
            for (int i = 1; i < 4; i++) document.lineTo(screenX(i), screenY(i));
            document.close();
            Path shade = new Path();
            shade.setFillType(Path.FillType.EVEN_ODD);
            shade.addRect(imageRect, Path.Direction.CW);
            shade.addPath(document);
            canvas.drawPath(shade, shadePaint);
            canvas.drawPath(document, outlinePaint);
            for (int i = 1; i <= 2; i++) {
                float ratio = i / 3f;
                canvas.drawLine(interpolate(screenX(0), screenX(1), ratio),
                        interpolate(screenY(0), screenY(1), ratio),
                        interpolate(screenX(3), screenX(2), ratio),
                        interpolate(screenY(3), screenY(2), ratio), gridPaint);
                canvas.drawLine(interpolate(screenX(0), screenX(3), ratio),
                        interpolate(screenY(0), screenY(3), ratio),
                        interpolate(screenX(1), screenX(2), ratio),
                        interpolate(screenY(1), screenY(2), ratio), gridPaint);
            }
        }

        private float interpolate(float a, float b, float ratio) { return a + (b - a) * ratio; }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int nearest = -1;
                float best = dp(60);
                for (int i = 0; i < 4; i++) {
                    float distance = (float) Math.hypot(event.getX() - screenX(i), event.getY() - screenY(i));
                    if (distance < best) { nearest = i; best = distance; }
                }
                if (nearest < 0) return false;
                return touchCorner(nearest, event.getActionMasked(), event.getX(), event.getY());
            }
            return touchCorner(activeCorner, event.getActionMasked(), event.getX(), event.getY());
        }

        private boolean touchCorner(int corner, int action, float x, float y) {
            if (!editingEnabled || image == null || imageRect.isEmpty() || corner < 0) return false;
            // Cropping is a one-finger gesture. Cancel rather than jump when another pointer joins.
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                int previous = activeCorner;
                activeCorner = -1;
                if (previous >= 0) handles[previous].invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                activeCorner = corner;
                dragOffsetX = screenX(corner) - x;
                dragOffsetY = screenY(corner) - y;
                getParent().requestDisallowInterceptTouchEvent(true);
                handles[corner].invalidate();
                return true;
            }
            if (corner != activeCorner) return false;
            if (action == MotionEvent.ACTION_MOVE) {
                moveCorner(corner, (x + dragOffsetX - imageRect.left) / imageRect.width(),
                        (y + dragOffsetY - imageRect.top) / imageRect.height());
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activeCorner = -1;
                handles[corner].invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) handles[corner].performClick();
                return true;
            }
            return true;
        }

        private void moveCorner(int corner, float x, float y) {
            float[] proposed = corners.clone();
            proposed[corner * 2] = Math.max(0f, Math.min(1f, x));
            proposed[corner * 2 + 1] = Math.max(0f, Math.min(1f, y));
            if (!validQuad(proposed)) return;
            System.arraycopy(proposed, 0, corners, 0, 8);
            requestLayout();
            invalidate();
        }

        private final class CornerHandle extends View {
            private static final int MOVE_LEFT = 0x01020001;
            private static final int MOVE_RIGHT = 0x01020002;
            private static final int MOVE_UP = 0x01020003;
            private static final int MOVE_DOWN = 0x01020004;
            private final int corner;
            private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            CornerHandle(int corner) {
                super(CropActivity.this);
                this.corner = corner;
                circlePaint.setColor(GREEN);
                borderPaint.setColor(Color.WHITE);
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(dp(2));
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(dp(12));
                textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                textPaint.setTextAlign(Paint.Align.CENTER);
            }

            @Override protected void onDraw(Canvas canvas) {
                float centerX = getWidth() / 2f;
                float centerY = getHeight() / 2f;
                float radius = dp(activeCorner == corner || isFocused() ? 16 : 13);
                canvas.drawCircle(centerX, centerY, radius, circlePaint);
                canvas.drawCircle(centerX, centerY, radius, borderPaint);
                float baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2;
                canvas.drawText(String.valueOf(corner + 1), centerX, baseline, textPaint);
            }

            @Override public boolean onTouchEvent(MotionEvent event) {
                // Raw coordinates stay stable as the handle itself follows the dragged corner.
                int[] location = new int[2];
                CropView.this.getLocationOnScreen(location);
                return touchCorner(corner, event.getActionMasked(),
                        event.getRawX() - location[0], event.getRawY() - location[1]);
            }

            @Override public boolean performClick() {
                super.performClick();
                requestFocus();
                return true;
            }

            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                if (!editingEnabled) return;
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_LEFT, "Move corner left"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_RIGHT, "Move corner right"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_UP, "Move corner up"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_DOWN, "Move corner down"));
            }

            @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
                if (editingEnabled && image != null) {
                    float x = corners[corner * 2];
                    float y = corners[corner * 2 + 1];
                    if (action == MOVE_LEFT) x -= .01f;
                    else if (action == MOVE_RIGHT) x += .01f;
                    else if (action == MOVE_UP) y -= .01f;
                    else if (action == MOVE_DOWN) y += .01f;
                    else return super.performAccessibilityAction(action, arguments);
                    moveCorner(corner, x, y);
                    announceForAccessibility("Corner " + (corner + 1) + ", "
                            + Math.round(corners[corner * 2] * 100) + " percent across, "
                            + Math.round(corners[corner * 2 + 1] * 100) + " percent down");
                    return true;
                }
                return super.performAccessibilityAction(action, arguments);
            }

            @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
                if (editingEnabled && image != null) {
                    float x = corners[corner * 2];
                    float y = corners[corner * 2 + 1];
                    float step = event.isShiftPressed() ? .025f : .005f;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) x -= step;
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) x += step;
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) y -= step;
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) y += step;
                    else return super.onKeyDown(keyCode, event);
                    moveCorner(corner, x, y);
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            }

            @Override protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
                super.onFocusChanged(gainFocus, direction, previous);
                invalidate();
            }
        }
    }
}
