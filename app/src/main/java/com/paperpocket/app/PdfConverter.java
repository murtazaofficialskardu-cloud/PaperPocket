package com.paperpocket.app;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Creates an image PDF while holding only the current decoded page in memory. */
public final class PdfConverter {
    private PdfConverter() { }

    public static final class Options {
        public String name = "Document";
        public int dpi = 300;
        public String paper = "A4";
        public boolean margin = true;
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    public static File convert(Context context, List<PageItem> sourcePages,
                               Options options, Progress progress) throws IOException {
        if (sourcePages == null || sourcePages.isEmpty()) {
            throw new IOException("Add at least one image before creating a PDF.");
        }
        if (sourcePages.size() > PdfStore.MAX_PAGES) {
            throw new IOException("A document can contain up to " + PdfStore.MAX_PAGES + " pages.");
        }
        List<PageItem> pages = new ArrayList<>(sourcePages.size());
        for (PageItem page : sourcePages) {
            if (page == null) throw new IOException("One of the selected pages is missing.");
            pages.add(page.copy());
        }
        Options selected = options == null ? new Options() : options;
        String paper = selected.paper;
        boolean margin = selected.margin;
        int dpi = Math.max(72, Math.min(600, selected.dpi));
        int points = "LETTER".equalsIgnoreCase(paper) ? 792 : 842;
        int maxDimension = Math.min(4096, (int) Math.ceil(points * dpi / 72.0));
        File directory = new File(context.getCacheDir(), "pdf_exports");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create the PDF export folder.");
        }
        File partial = File.createTempFile("export-", ".part", directory);
        File complete = new File(directory, partial.getName().replace(".part", ".pdf"));
        boolean finished = false;
        boolean promoted = false;
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                StreamingImagePdf writer = new StreamingImagePdf(output, PdfStore.MAX_PDF_BYTES);
                for (int i = 0; i < pages.size(); i++) {
                    checkCancelled();
                    Bitmap bitmap = null;
                    File jpeg = null;
                    try {
                        // ImageLoader composites transparency onto white before returning pixels.
                        bitmap = ImageLoader.load(pages.get(i), maxDimension);
                        PdfGeometry.Layout layout = PdfGeometry.layout(bitmap.getWidth(),
                                bitmap.getHeight(), paper, margin);
                        jpeg = File.createTempFile("page-", ".jpg", directory);
                        int width = bitmap.getWidth();
                        int height = bitmap.getHeight();
                        try (FileOutputStream image = new FileOutputStream(jpeg)) {
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 98, image)) {
                                throw new IOException("Unable to encode a page image.");
                            }
                        }
                        bitmap.recycle();
                        bitmap = null;
                        checkCancelled();
                        writer.addPage(layout, jpeg, width, height);
                    } finally {
                        if (bitmap != null) bitmap.recycle();
                        if (jpeg != null) jpeg.delete();
                    }
                    if (progress != null) progress.onProgress(i + 1, pages.size());
                }
                checkCancelled();
                writer.finish();
                output.getFD().sync();
            }
            if (partial.length() == 0 || complete.exists() || !partial.renameTo(complete)) {
                throw new IOException("Unable to finish writing the PDF.");
            }
            promoted = true;
            finished = true;
            return complete;
        } catch (OutOfMemoryError error) {
            throw new IOException("This page needs more memory. Try Standard quality.", error);
        } catch (RuntimeException error) {
            throw new IOException("Unable to convert one of the selected images.", error);
        } finally {
            if (!finished) {
                partial.delete();
                if (promoted) complete.delete();
            }
        }
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("PDF creation was cancelled.");
        }
    }
}
