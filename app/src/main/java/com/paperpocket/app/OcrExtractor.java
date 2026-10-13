package com.paperpocket.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional offline Latin-script OCR. Never modifies its source PDF. */
public final class OcrExtractor {
    public static final int MAX_PAGES = 50;
    public static final int MAX_TEXT_CHARACTERS = 1_000_000;
    private static final long TOTAL_MILLISECONDS = 10 * 60_000L;
    private static final long PAGE_MILLISECONDS = 45_000;
    private static final Semaphore MODEL_SLOT = new Semaphore(1, true);
    private static final Executor DIRECT = Runnable::run;

    private OcrExtractor() { }

    public static final class Options {
        public int maxPages = MAX_PAGES;
        public int maxCharacters = MAX_TEXT_CHARACTERS;
        public int maxDimension = 2400;
        public int maxPixels = 4_000_000;

        private Options validatedCopy() throws IOException {
            if (maxPages < 1 || maxPages > 100 || maxCharacters < 1
                    || maxCharacters > MAX_TEXT_CHARACTERS || maxDimension < 128
                    || maxDimension > 3000 || maxPixels < 16_384 || maxPixels > 4_000_000) {
                throw new IOException("Text extraction limits are invalid.");
            }
            Options copy = new Options();
            copy.maxPages = maxPages; copy.maxCharacters = maxCharacters;
            copy.maxDimension = maxDimension; copy.maxPixels = maxPixels;
            return copy;
        }
    }

    /** Run on a worker thread. Empty string means no text was recognized. */
    public static String extract(Context context, File document, PdfConverter.Progress progress)
            throws IOException {
        return extract(context, document, new Options(), progress);
    }

    public static String extract(Context context, File document, Options selected,
                                 PdfConverter.Progress progress) throws IOException {
        checkCancelled();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("Text extraction must run in the background.");
        }
        Options options = (selected == null ? new Options() : selected).validatedCopy();
        if (context == null || document == null || !document.isFile()
                || !document.canRead() || document.length() < 1
                || document.length() > PdfStore.MAX_PDF_BYTES) {
            throw new IOException("Choose a readable saved PDF for text extraction.");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_MILLISECONDS);
        try {
            if (!MODEL_SLOT.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new IOException("The previous text extraction is still finishing. Try again.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cancelled();
        }
        ModelRun model = null;
        try (OcrPdfRasterSession pdf = OcrPdfRasterSession.open(context, document, options)) {
            checkDeadline(deadline);
            model = new ModelRun(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS));
            StringBuilder output = new StringBuilder();
            boolean foundText = false;
            int pages = pdf.pageCount();
            if (progress != null) progress.onProgress(0, pages);
            for (int index = 0; index < pages; index++) {
                checkDeadline(deadline);
                Bitmap bitmap;
                try { bitmap = pdf.render(index); }
                catch (OutOfMemoryError unavailable) {
                    throw new IOException("There is not enough memory to extract this page.", unavailable);
                }
                String recognized = model.recognize(bitmap, Math.min(deadline,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PAGE_MILLISECONDS))).trim();
                if (!recognized.isEmpty()) foundText = true;
                String prefix = (index == 0 ? "" : "\n\n")
                        + "[Page " + (index + 1) + " of " + pages + "]\n";
                String text = recognized.isEmpty() ? "[No text recognized]" : recognized;
                if ((long) output.length() + prefix.length() + text.length() > options.maxCharacters) {
                    throw new IOException("The extracted text is too long. Split this PDF and extract smaller parts.");
                }
                output.append(prefix).append(text);
                checkDeadline(deadline);
                if (progress != null) progress.onProgress(index + 1, pages);
                checkCancelled();
            }
            return foundText ? output.toString() : "";
        } catch (RuntimeException unavailable) {
            throw new IOException("Text extraction could not finish on this device.", unavailable);
        } catch (OutOfMemoryError unavailable) {
            throw new IOException("There is not enough memory for text extraction. Try a smaller PDF.", unavailable);
        } finally {
            if (model == null) MODEL_SLOT.release();
            else model.close();
        }
    }

    static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw cancelled();
    }

    static InterruptedIOException cancelled() {
        return new InterruptedIOException("Text extraction was cancelled.");
    }

    private static void checkDeadline(long deadline) throws IOException {
        checkCancelled();
        if (System.nanoTime() >= deadline) {
            throw new IOException("Text extraction timed out. Try a smaller PDF.");
        }
    }

    /** Retains an in-flight bitmap until ML Kit signals completion, even on cancel. */
    private static final class ModelRun implements AutoCloseable {
        private final TextRecognizer recognizer;
        private final AtomicBoolean retired = new AtomicBoolean();
        private Task<Text> activeTask;
        private Bitmap activeBitmap;

        ModelRun(TextRecognizer recognizer) { this.recognizer = recognizer; }

        String recognize(Bitmap bitmap, long deadline) throws IOException {
            activeBitmap = bitmap;
            checkCancelled();
            try {
                CountDownLatch finished = new CountDownLatch(1);
                activeTask = recognizer.process(InputImage.fromBitmap(bitmap, 0));
                activeTask.addOnCompleteListener(DIRECT, ignored -> finished.countDown());
                while (!activeTask.isComplete()) {
                    checkDeadline(deadline);
                    try { finished.await(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw cancelled();
                    }
                }
                checkDeadline(deadline);
                if (!activeTask.isSuccessful()) {
                    throw new IOException("The offline text-recognition model could not read this page.",
                            activeTask.getException());
                }
                Text result = activeTask.getResult();
                String text = result == null ? "" : result.getText();
                releaseBitmap();
                activeTask = null;
                return text;
            } catch (RuntimeException failure) {
                throw new IOException("The offline text-recognition model could not read this page.", failure);
            }
        }

        private void releaseBitmap() {
            Bitmap bitmap = activeBitmap;
            activeBitmap = null;
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }

        @Override public void close() {
            if (!retired.compareAndSet(false, true)) return;
            Task<Text> task = activeTask;
            if (task != null && !task.isComplete()) {
                // ML Kit offers no cancellation token for this operation. Returning
                // promptly is safe; one global slot bounds the retained page until
                // its completion callback can release pixels and the recognizer.
                task.addOnCompleteListener(DIRECT, ignored -> dispose());
            } else dispose();
        }

        private void dispose() {
            try { releaseBitmap(); }
            finally {
                try { recognizer.close(); }
                finally { MODEL_SLOT.release(); }
            }
        }
    }
}
