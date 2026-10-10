package com.paperpocket.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A PDF 1.4 image writer that streams JPEG bytes instead of retaining page bitmaps.
 * The caller owns the output stream and must call finish() before closing it.
 */
public final class StreamingImagePdf {
    private static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;
    private final CountingOutputStream output;
    private final List<Long> offsets = new ArrayList<>();
    private int pageCount;
    private boolean finished;
    private boolean failed;

    public StreamingImagePdf(OutputStream output) throws IOException {
        this(output, DEFAULT_MAX_BYTES);
    }

    public StreamingImagePdf(OutputStream output, long maxBytes) throws IOException {
        if (output == null || maxBytes <= 0) throw new IllegalArgumentException("Invalid PDF output.");
        this.output = new CountingOutputStream(output, maxBytes);
        offsets.add(0L);
        offsets.add(0L);
        offsets.add(0L);
        write("%PDF-1.4\n");
        this.output.write(new byte[]{'%', (byte) 0xe2, (byte) 0xe3, (byte) 0xcf, (byte) 0xd3, '\n'});
    }

    /** JPEG must be a complete RGB image, already composited against white. */
    public void addPage(PdfGeometry.Layout layout, File jpeg, int pixelWidth, int pixelHeight)
            throws IOException {
        requireOpen();
        if (layout == null || layout.pageWidth <= 0 || layout.pageHeight <= 0
                || layout.imageWidth <= 0 || layout.imageHeight <= 0
                || !Float.isFinite(layout.left) || !Float.isFinite(layout.top)
                || !Float.isFinite(layout.imageWidth) || !Float.isFinite(layout.imageHeight)
                || pixelWidth <= 0 || pixelHeight <= 0
                || jpeg == null || !jpeg.isFile() || jpeg.length() < 4) {
            throw new IOException("A PDF page needs a readable JPEG and positive dimensions.");
        }
        int pageObject = 3 + pageCount * 3;
        int contentObject = pageObject + 1;
        int imageObject = pageObject + 2;
        long jpegLength = jpeg.length();
        try (FileInputStream image = new FileInputStream(jpeg)) {
            if (image.read() != 0xff || image.read() != 0xd8) {
                throw new IOException("The PDF page image is not a JPEG.");
            }
            image.getChannel().position(0);
            startObject(pageObject);
            write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + layout.pageWidth + " "
                    + layout.pageHeight + "] /Resources << /ProcSet [/PDF /ImageC] /XObject << /Im0 "
                    + imageObject + " 0 R >> >> /Contents " + contentObject + " 0 R >>\nendobj\n");

            // Convert top-left layout coordinates to PDF's bottom-left origin.
            float bottom = layout.pageHeight - layout.top - layout.imageHeight;
            String commands = "q\n" + number(layout.imageWidth) + " 0 0 "
                    + number(layout.imageHeight) + " " + number(layout.left) + " "
                    + number(bottom) + " cm\n/Im0 Do\nQ\n";
            byte[] content = commands.getBytes(StandardCharsets.US_ASCII);
            startObject(contentObject);
            write("<< /Length " + content.length + " >>\nstream\n");
            output.write(content);
            write("endstream\nendobj\n");

            startObject(imageObject);
            write("<< /Type /XObject /Subtype /Image /Width " + pixelWidth + " /Height "
                    + pixelHeight + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"
                    + " /Length " + jpegLength + " >>\nstream\n");
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            int read;
            while ((read = image.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("PDF creation was cancelled.");
                copied += read;
                if (copied > jpegLength) throw new IOException("The page image changed while exporting.");
                output.write(buffer, 0, read);
            }
            if (copied != jpegLength) throw new IOException("The page image could not be read completely.");
            write("\nendstream\nendobj\n");
            pageCount++;
        } catch (IOException | RuntimeException error) {
            failed = true;
            throw error;
        }
    }

    /** Writes the page tree and standard byte-offset cross-reference table. */
    public void finish() throws IOException {
        if (finished) return;
        requireOpen();
        if (pageCount == 0) throw new IOException("A PDF must contain at least one page.");
        try {
            startObject(1);
            write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            startObject(2);
            write("<< /Type /Pages /Count " + pageCount + " /Kids [");
            for (int i = 0; i < pageCount; i++) write((3 + i * 3) + " 0 R ");
            write("] >>\nendobj\n");

            long xref = output.count;
            write("xref\n0 " + offsets.size() + "\n0000000000 65535 f \n");
            for (int i = 1; i < offsets.size(); i++) {
                long offset = offsets.get(i);
                if (offset <= 0 || offset >= 10_000_000_000L) {
                    throw new IOException("Invalid PDF object offset.");
                }
                write(String.format(Locale.US, "%010d 00000 n \n", offset));
            }
            write("trailer\n<< /Size " + offsets.size() + " /Root 1 0 R >>\nstartxref\n"
                    + xref + "\n%%EOF\n");
            output.flush();
            finished = true;
        } catch (IOException | RuntimeException error) {
            failed = true;
            throw error;
        }
    }

    public int getPageCount() { return pageCount; }

    private void requireOpen() throws IOException {
        if (finished) throw new IOException("The PDF is already complete.");
        if (failed) throw new IOException("The PDF export failed and cannot be continued.");
    }

    private void startObject(int number) throws IOException {
        while (offsets.size() <= number) offsets.add(0L);
        offsets.set(number, output.count);
        write(number + " 0 obj\n");
    }

    private void write(String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String number(float value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream destination;
        private final long maximum;
        private long count;

        CountingOutputStream(OutputStream destination, long maximum) {
            this.destination = destination;
            this.maximum = maximum;
        }

        @Override public void write(int value) throws IOException {
            reserve(1);
            destination.write(value);
            count++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            destination.write(bytes, offset, length);
            count += length;
        }

        private void reserve(int length) throws IOException {
            if (length < 0 || count > maximum - length) {
                throw new IOException("PDF files must be smaller than 512 MB.");
            }
        }

        @Override public void flush() throws IOException { destination.flush(); }
    }
}
