package com.paperpocket.app;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded reader for PaperPocket's single-JPEG-per-page PDF writer, not a general PDF parser. */
final class ImagePdfReader implements Closeable {
    static final class Unsupported extends IOException {
        Unsupported() {
            super("This PDF is damaged or uses an unsupported format. Merge and extract support "
                    + "PDFs created by PaperPocket.");
        }
    }

    static final class Page {
        final PdfGeometry.Layout layout;
        final int pixelWidth, pixelHeight;
        final long jpegOffset, jpegLength;
        Page(PdfGeometry.Layout layout, int pixelWidth, int pixelHeight, long offset, long length) {
            this.layout = layout;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            jpegOffset = offset;
            jpegLength = length;
        }
    }

    private static final byte[] HEADER = {'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n',
            '%', (byte) 0xe2, (byte) 0xe3, (byte) 0xcf, (byte) 0xd3, '\n'};
    private static final String DECIMAL = "(-?[0-9]+(?:\\.[0-9]+)?)";
    private static final Pattern COMMANDS = Pattern.compile("q\\n" + DECIMAL + " 0 0 "
            + DECIMAL + " " + DECIMAL + " " + DECIMAL + " cm\\n/Im0 Do\\nQ\\n");
    private final File file;
    private final RandomAccessFile input;
    final long length, modified;
    final List<Page> pages;
    private boolean imageOpen;

    ImagePdfReader(File file, int maxPages, long maxBytes) throws IOException {
        cancelled();
        if (file == null || !file.isFile() || file.length() <= 0) throw new IOException("Choose a readable PDF.");
        if (file.length() > maxBytes) throw new IOException("PDF files must be smaller than 512 MB.");
        this.file = file;
        length = file.length();
        modified = file.lastModified();
        input = new RandomAccessFile(file, "r");
        try {
            pages = Collections.unmodifiableList(parse(maxPages));
            unchanged();
        } catch (IOException | RuntimeException error) {
            input.close();
            if (error instanceof IOException) throw (IOException) error;
            throw new Unsupported();
        }
    }

    private List<Page> parse(int maxPages) throws IOException {
        if (length < HEADER.length || !Arrays.equals(HEADER, bytes(0, HEADER.length))) throw new Unsupported();
        int tailLength = (int) Math.min(length, 128);
        String tail = new String(bytes(length - tailLength, tailLength), StandardCharsets.US_ASCII);
        Matcher ending = Pattern.compile("startxref\\n([0-9]{1,10})\\n%%EOF\\n$").matcher(tail);
        if (!ending.find()) throw new Unsupported();
        long xref = Long.parseLong(ending.group(1));
        if (xref < HEADER.length || xref >= length || length - xref > 64_000) throw new Unsupported();
        String[] lines = ascii(xref, length - xref, 64_000).split("\\n", -1);
        if (lines.length < 12 || !lines[0].equals("xref") || !lines[1].matches("0 [0-9]{1,5}")) throw new Unsupported();
        int size = Integer.parseInt(lines[1].substring(2));
        if (size < 6 || (size - 3) % 3 != 0 || (size - 3) / 3 > maxPages
                || lines.length != size + 8 || !lines[2].equals("0000000000 65535 f ")) throw new Unsupported();
        int count = (size - 3) / 3;
        long[] offsets = new long[size];
        for (int id = 1; id < size; id++) {
            cancelled();
            String entry = lines[id + 2];
            if (!entry.matches("[0-9]{10} 00000 n ")) throw new Unsupported();
            offsets[id] = Long.parseLong(entry.substring(0, 10));
            if (offsets[id] < HEADER.length || offsets[id] >= xref) throw new Unsupported();
        }
        int trailer = size + 2;
        if (!lines[trailer].equals("trailer")
                || !lines[trailer + 1].equals("<< /Size " + size + " /Root 1 0 R >>")
                || !lines[trailer + 2].equals("startxref") || !lines[trailer + 3].equals(Long.toString(xref))
                || !lines[trailer + 4].equals("%%EOF") || !lines[trailer + 5].isEmpty()) throw new Unsupported();
        if (offsets[3] != HEADER.length || offsets[1] <= offsets[size - 1]
                || offsets[2] <= offsets[1]) throw new Unsupported();
        for (int id = 4; id < size; id++) if (offsets[id] <= offsets[id - 1]) throw new Unsupported();
        if (!ascii(offsets[1], offsets[2] - offsets[1], 256)
                .equals("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")) throw new Unsupported();
        StringBuilder tree = new StringBuilder("2 0 obj\n<< /Type /Pages /Count " + count + " /Kids [");
        for (int page = 0; page < count; page++) tree.append(3 + page * 3).append(" 0 R ");
        tree.append("] >>\nendobj\n");
        if (!ascii(offsets[2], xref - offsets[2], 16_384).equals(tree.toString())) throw new Unsupported();
        List<Page> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            cancelled();
            int pageId = 3 + index * 3, contentId = pageId + 1, imageId = pageId + 2;
            String pageObject = ascii(offsets[pageId], offsets[contentId] - offsets[pageId], 1024);
            String prefix = pageId + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ";
            String suffix = "] /Resources << /ProcSet [/PDF /ImageC] /XObject << /Im0 "
                    + imageId + " 0 R >> >> /Contents " + contentId + " 0 R >>\nendobj\n";
            Matcher page = Pattern.compile(Pattern.quote(prefix) + "([0-9]{1,9}) ([0-9]{1,9})"
                    + Pattern.quote(suffix)).matcher(pageObject);
            if (!page.matches()) throw new Unsupported();
            int width = positive(page.group(1), 1_000_000), height = positive(page.group(2), 1_000_000);
            String content = ascii(offsets[contentId], offsets[imageId] - offsets[contentId], 1024);
            Matcher contentHeader = Pattern.compile(Pattern.quote(contentId + " 0 obj\n<< /Length ")
                    + "([0-9]{1,3})" + Pattern.quote(" >>\nstream\n")).matcher(content);
            if (!contentHeader.lookingAt()) throw new Unsupported();
            int contentLength = Integer.parseInt(contentHeader.group(1)), start = contentHeader.end();
            if (contentLength <= 0 || start + contentLength > content.length()
                    || !content.substring(start + contentLength).equals("endstream\nendobj\n")) throw new Unsupported();
            Matcher commands = COMMANDS.matcher(content.substring(start, start + contentLength));
            if (!commands.matches()) throw new Unsupported();
            float imageWidth = Float.parseFloat(commands.group(1)), imageHeight = Float.parseFloat(commands.group(2));
            float left = Float.parseFloat(commands.group(3)), bottom = Float.parseFloat(commands.group(4));
            PdfGeometry.Layout layout;
            try { layout = PdfGeometry.placedImage(width, height, left, height - bottom - imageHeight, imageWidth, imageHeight); }
            catch (IllegalArgumentException invalid) { throw new Unsupported(); }
            long imageLimit = imageId + 1 < size ? offsets[imageId + 1] : offsets[1];
            byte[] probe = bytes(offsets[imageId], (int) Math.min(512, imageLimit - offsets[imageId]));
            int marker = indexOf(probe, "\nstream\n".getBytes(StandardCharsets.US_ASCII));
            if (marker < 0) throw new Unsupported();
            String imageHeader = text(probe, marker);
            Matcher image = Pattern.compile(Pattern.quote(imageId + " 0 obj\n<< /Type /XObject /Subtype /Image /Width ")
                    + "([0-9]{1,9})" + Pattern.quote(" /Height ") + "([0-9]{1,9})"
                    + Pattern.quote(" /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ")
                    + "([0-9]{1,10})" + Pattern.quote(" >>")).matcher(imageHeader);
            if (!image.matches()) throw new Unsupported();
            int pixelWidth = positive(image.group(1), 65535), pixelHeight = positive(image.group(2), 65535);
            long jpegLength = Long.parseLong(image.group(3)), jpegOffset = offsets[imageId] + marker + 8;
            byte[] terminator = "\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII);
            if (jpegLength < 4 || jpegLength > length || jpegOffset + jpegLength + terminator.length != imageLimit
                    || !Arrays.equals(terminator, bytes(jpegOffset + jpegLength, terminator.length))) throw new Unsupported();
            if (!Arrays.equals(new byte[]{(byte) 0xff, (byte) 0xd8}, bytes(jpegOffset, 2))
                    || !Arrays.equals(new byte[]{(byte) 0xff, (byte) 0xd9}, bytes(jpegOffset + jpegLength - 2, 2))) throw new Unsupported();
            result.add(new Page(layout, pixelWidth, pixelHeight, jpegOffset, jpegLength));
        }
        return result;
    }

    InputStream image(Page page) throws IOException {
        cancelled();
        unchanged();
        if (imageOpen) throw new IOException("Close the previous PDF image before opening another.");
        input.seek(page.jpegOffset);
        imageOpen = true;
        return new InputStream() {
            long remaining = page.jpegLength;
            boolean closed;
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] buffer, int offset, int size) throws IOException {
                cancelled();
                if (closed) throw new IOException("The PDF image stream is closed.");
                if (size == 0) return 0;
                if (remaining == 0) return -1;
                int read = input.read(buffer, offset, (int) Math.min(size, remaining));
                if (read < 0) throw new IOException("The PDF changed while it was being read.");
                remaining -= read;
                return read;
            }
            @Override public void close() { if (!closed) { closed = true; imageOpen = false; } }
        };
    }

    void unchanged() throws IOException {
        if (file.length() != length || file.lastModified() != modified || input.length() != length) {
            throw new IOException("A selected PDF changed. Choose the files again.");
        }
    }

    private byte[] bytes(long offset, int size) throws IOException {
        cancelled();
        if (offset < 0 || size < 0 || offset > length - size) throw new Unsupported();
        byte[] result = new byte[size];
        input.seek(offset);
        input.readFully(result);
        return result;
    }

    private String ascii(long offset, long size, int maximum) throws IOException {
        if (size <= 0 || size > maximum) throw new Unsupported();
        byte[] bytes = bytes(offset, (int) size);
        return text(bytes, bytes.length);
    }

    private static String text(byte[] bytes, int size) throws IOException {
        for (int index = 0; index < size; index++) {
            int value = bytes[index] & 255;
            if (value != '\n' && value != '\r' && value != '\t' && (value < 32 || value > 126)) throw new Unsupported();
        }
        return new String(bytes, 0, size, StandardCharsets.US_ASCII);
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        for (int at = 0; at <= bytes.length - pattern.length; at++) {
            int index = 0;
            while (index < pattern.length && bytes[at + index] == pattern[index]) index++;
            if (index == pattern.length) return at;
        }
        return -1;
    }

    private static int positive(String value, int maximum) throws IOException {
        int number = Integer.parseInt(value);
        if (number <= 0 || number > maximum) throw new Unsupported();
        return number;
    }

    static void cancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("PDF operation was cancelled.");
    }

    @Override public void close() throws IOException { input.close(); }
}
