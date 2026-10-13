package com.paperpocket.app;

import android.content.Context;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Lossless merge and ordered extraction of PDFs created by PaperPocket. */
public final class PdfOperations {
    private PdfOperations() { }

    public static final class UnsupportedPdfException extends IOException {
        UnsupportedPdfException(IOException cause) { super(cause.getMessage(), cause); }
    }

    /** Files are appended in list order; sources remain unchanged. Caller owns the cache result. */
    public static File merge(Context context, List<File> files, PdfConverter.Progress progress)
            throws IOException {
        try {
            return LosslessPdfOperations.merge(directory(context), files, PdfStore.MAX_PAGES,
                    PdfStore.MAX_PDF_BYTES, adapt(progress));
        } catch (ImagePdfReader.Unsupported unsupported) { throw new UnsupportedPdfException(unsupported); }
    }

    /** Indices are zero-based and emitted in requested order; duplicate pages are rejected. */
    public static File extract(Context context, File file, int[] zeroBasedPages,
                               PdfConverter.Progress progress) throws IOException {
        try {
            return LosslessPdfOperations.extract(directory(context), file, zeroBasedPages,
                    PdfStore.MAX_PAGES, PdfStore.MAX_PDF_BYTES, adapt(progress));
        } catch (ImagePdfReader.Unsupported unsupported) { throw new UnsupportedPdfException(unsupported); }
    }

    /** Parses one-based input such as 1-3,5 into ordered zero-based indices. */
    public static int[] parsePageSelection(String input, int pageCount) throws IOException {
        return PdfPageSelection.parse(input, pageCount, PdfStore.MAX_PAGES);
    }

    private static File directory(Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("PDF operations must run in the background.");
        ImagePdfReader.cancelled();
        Context application = context.getApplicationContext();
        return new File((application == null ? context : application).getCacheDir(), "pdf_exports");
    }

    private static LosslessPdfOperations.Progress adapt(PdfConverter.Progress progress) {
        return progress == null ? null : progress::onProgress;
    }
}
