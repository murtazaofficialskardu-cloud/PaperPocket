package com.paperpocket.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.IOException;

/** Decodes one imported page, honoring camera orientation and document filters. */
public final class ImageLoader {
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_DECODE_PIXELS = 16_000_000L;

    private ImageLoader() {}

    /** The caller owns the returned bitmap and should recycle it after use. */
    public static Bitmap load(PageItem item, int maxDimension) throws IOException {
        checkCancelled();
        if (item == null || item.path == null || item.path.isEmpty()) {
            throw new IOException("This page has no image file.");
        }
        if (maxDimension < 1) {
            throw new IllegalArgumentException("maxDimension must be positive.");
        }
        int rotation = ((item.rotation % 360) + 360) % 360;
        if (rotation % 90 != 0) {
            throw new IOException("Page rotation must use 90-degree increments.");
        }
        File file = new File(item.path);
        if (!file.isFile()) {
            throw new IOException("The page image is no longer available.");
        }
        int limit = Math.min(maxDimension, MAX_DIMENSION);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(item.path, bounds);
        checkCancelled();
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("This file is not a supported image.");
        }

        int sample = 1;
        long longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (sample <= (1 << 29)
                && (longest / (sample * 2L) >= limit
                || estimatedPixels(bounds.outWidth, bounds.outHeight, sample) > MAX_DECODE_PIXELS)) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inMutable = true;
        options.inScaled = false;
        Bitmap bitmap = null;
        boolean returned = false;
        try {
            bitmap = BitmapFactory.decodeFile(item.path, options);
            checkCancelled();
            if (bitmap == null) {
                throw new IOException("The image could not be decoded. Please import it again.");
            }
            if (item.filter != null && item.filter != PageItem.Filter.ORIGINAL) {
                applyFilter(bitmap, item.filter == PageItem.Filter.BLACK_WHITE);
            } else {
                flattenOnWhite(bitmap);
            }

            Matrix transform = orientationMatrix(readOrientation(item.path));
            checkCancelled();
            transform.postRotate(rotation);
            float scale = Math.min(1f,
                    limit / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
            transform.postScale(scale, scale);
            if (!transform.isIdentity()) {
                Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), transform, true);
                if (transformed != bitmap) {
                    bitmap.recycle();
                    bitmap = transformed;
                }
            }
            checkCancelled();
            returned = true;
            return bitmap;
        } catch (OutOfMemoryError error) {
            throw new IOException("There is not enough memory to process this image.", error);
        } catch (RuntimeException error) {
            throw new IOException("This image could not be processed.", error);
        } finally {
            // Checked cancellation and processing failures retain no decoded pixel buffer.
            if (!returned && bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static long estimatedPixels(int width, int height, int sample) {
        return ((width + (long) sample - 1) / sample)
                * ((height + (long) sample - 1) / sample);
    }

    private static int readOrientation(String path) {
        try {
            return new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) {
            // PNGs, screenshots, and images with damaged metadata still decode normally.
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Matrix orientationMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setValues(new float[] {0, 1, 0, 1, 0, 0, 0, 0, 1});
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setValues(new float[] {0, -1, 0, -1, 0, 0, 0, 0, 1});
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            default:
                break;
        }
        return matrix;
    }

    /** Two row-buffer passes avoid a second full-sized bitmap or pixel array. */
    private static void applyFilter(Bitmap bitmap, boolean grayscale) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] row = new int[width];
        long[] histogram = new long[256];
        for (int y = 0; y < height; y++) {
            checkCancelled();
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                histogram[luminance(onWhite(row[x]))]++;
            }
        }
        long pixels = (long) width * height;
        int low = percentile(histogram, Math.max(1, pixels / 200));
        int high = percentile(histogram, Math.max(1, pixels - pixels / 200));
        // A nearly uniform sheet must not amplify tiny JPEG variations into noise.
        if (high - low < 64) {
            low = Math.max(0, high - 64);
            high = Math.min(255, low + 64);
        }
        int[] curve = new int[256];
        float contrast = grayscale ? 1.20f : 1.08f;
        double gamma = grayscale ? 0.82 : 0.90;
        for (int i = 0; i < 256; i++) {
            float normalized = (i - low) / (float) Math.max(1, high - low);
            normalized = Math.max(0f, Math.min(1f, (normalized - .5f) * contrast + .5f));
            curve[i] = Math.max(0, Math.min(255, (int) Math.round(255 * Math.pow(normalized, gamma))));
        }
        for (int y = 0; y < height; y++) {
            checkCancelled();
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = onWhite(row[x]);
                if (grayscale) {
                    int value = curve[luminance(color)];
                    row[x] = 0xff000000 | (value << 16) | (value << 8) | value;
                } else {
                    row[x] = 0xff000000 | (curve[(color >>> 16) & 255] << 16)
                            | (curve[(color >>> 8) & 255] << 8) | curve[color & 255];
                }
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
        bitmap.setHasAlpha(false);
    }

    /** JPEG and PDF pages have a white paper background, including transparent imports. */
    private static void flattenOnWhite(Bitmap bitmap) throws IOException {
        if (!bitmap.hasAlpha()) return;
        int width = bitmap.getWidth();
        int[] row = new int[width];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            checkCancelled();
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            boolean changed = false;
            for (int x = 0; x < width; x++) {
                if ((row[x] >>> 24) != 255) {
                    row[x] = onWhite(row[x]);
                    changed = true;
                }
            }
            if (changed) bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
        // This is safe only after every nonopaque pixel has actually been composited.
        bitmap.setHasAlpha(false);
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Image processing was cancelled.");
        }
    }

    private static int percentile(long[] histogram, long count) {
        long cumulative = 0;
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative >= count) return i;
        }
        return 255;
    }

    private static int luminance(int color) {
        return (77 * ((color >>> 16) & 255) + 150 * ((color >>> 8) & 255)
                + 29 * (color & 255) + 128) >>> 8;
    }

    private static int onWhite(int color) {
        int alpha = color >>> 24;
        if (alpha == 255) return color;
        int white = 255 * (255 - alpha);
        int red = (((color >>> 16) & 255) * alpha + white + 127) / 255;
        int green = (((color >>> 8) & 255) * alpha + white + 127) / 255;
        int blue = ((color & 255) * alpha + white + 127) / 255;
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }
}
