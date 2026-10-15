package com.paperpocket.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Composes front and back onto one bounded, white A4 image and streams it to PDF. */
public final class IdCardPdfConverter {
    private IdCardPdfConverter() { }

    public static File convert(Context context, List<PageItem> sourcePages,
                               PdfConverter.Options options, PdfConverter.Progress progress)
            throws IOException {
        checkCancelled();
        if (sourcePages == null || sourcePages.size() != 2) {
            throw new IOException("ID-card mode needs exactly two images: front, then back.");
        }
        List<PageItem> pages = new ArrayList<>(2);
        for (PageItem page : sourcePages) {
            if (page == null) throw new IOException("One of the ID-card images is missing.");
            pages.add(page.copy());
        }
        PdfConverter.Options selected = options == null ? new PdfConverter.Options() : options;
        PdfConverter.Profile profile = selected.profile == null
                ? PdfConverter.Profile.BALANCED : selected.profile;
        int dpi = Math.max(72, Math.min(300, selected.dpi > 0 ? selected.dpi : profile.dpi));
        int quality = Math.max(1, Math.min(100,
                selected.jpegQuality > 0 ? selected.jpegQuality : profile.jpegQuality));
        int width = IdCardLayout.rasterWidth(dpi);
        int height = IdCardLayout.rasterHeight(dpi);
        if ((long) width * height > IdCardLayout.MAX_PIXELS) {
            throw new IOException("This ID-card resolution is too large.");
        }
        File directory = new File(context.getCacheDir(), "pdf_exports");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create the PDF export folder.");
        }
        File partial = File.createTempFile("id-card-", ".part", directory);
        File complete = new File(directory, partial.getName().replace(".part", ".pdf"));
        File jpeg = null;
        Bitmap sheet = null;
        boolean finished = false;
        boolean promoted = false;
        try {
            checkCancelled();
            sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            sheet.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(sheet);
            canvas.scale(width / (float) IdCardLayout.PAGE_WIDTH,
                    height / (float) IdCardLayout.PAGE_HEIGHT);
            Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            framePaint.setColor(Color.rgb(195, 195, 195));
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(.4f);
            // Each input is decoded only to the longest pixel edge its card frame needs.
            int maxImageEdge = Math.max(1,
                    (int) Math.ceil(IdCardLayout.CARD_WIDTH * width / IdCardLayout.PAGE_WIDTH));
            for (int side = 0; side < 2; side++) {
                checkCancelled();
                Bitmap image = null;
                try {
                    image = ImageLoader.load(pages.get(side), maxImageEdge);
                    IdCardLayout.Placement frame = IdCardLayout.frame(side);
                    IdCardLayout.Placement fitted = IdCardLayout.fit(side,
                            image.getWidth(), image.getHeight());
                    // Draw the guide immediately outside the fitted card area.
                    canvas.drawRect(frame.left - .4f, frame.top - .4f,
                            frame.left + frame.width + .4f, frame.top + frame.height + .4f,
                            framePaint);
                    canvas.drawBitmap(image, null, new RectF(fitted.left, fitted.top,
                            fitted.left + fitted.width, fitted.top + fitted.height), imagePaint);
                } finally {
                    if (image != null) image.recycle();
                }
                checkCancelled();
                if (progress != null) progress.onProgress(side + 1, 2);
            }
            checkCancelled();
            jpeg = File.createTempFile("id-card-sheet-", ".jpg", directory);
            try (FileOutputStream imageOutput = new FileOutputStream(jpeg)) {
                if (!sheet.compress(Bitmap.CompressFormat.JPEG, quality, imageOutput)) {
                    throw new IOException("Unable to encode the ID-card images.");
                }
            }
            sheet.recycle();
            sheet = null;
            checkCancelled();
            try (FileOutputStream output = new FileOutputStream(partial)) {
                StreamingImagePdf writer = new StreamingImagePdf(output, PdfStore.MAX_PDF_BYTES);
                writer.addPage(PdfGeometry.fullPage(IdCardLayout.PAGE_WIDTH,
                        IdCardLayout.PAGE_HEIGHT), jpeg, width, height);
                checkCancelled();
                writer.finish();
                output.getFD().sync();
            }
            checkCancelled();
            if (partial.length() == 0 || complete.exists() || !partial.renameTo(complete)) {
                throw new IOException("Unable to finish writing the ID-card PDF.");
            }
            promoted = true;
            // Cancellation that races with promotion must not return a completed export.
            checkCancelled();
            finished = true;
            return complete;
        } catch (OutOfMemoryError error) {
            throw new IOException("There is not enough memory. Try Compact quality.", error);
        } catch (RuntimeException error) {
            throw new IOException("Unable to process the ID-card images.", error);
        } finally {
            if (sheet != null) sheet.recycle();
            if (jpeg != null) jpeg.delete();
            if (!finished) {
                partial.delete();
                if (promoted) complete.delete();
            }
        }
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("ID-card PDF creation was cancelled.");
        }
    }
}
