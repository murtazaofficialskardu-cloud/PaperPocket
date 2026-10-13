package com.paperpocket.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Pure Java operations for PaperPocket image PDFs, with one source descriptor open at a time. */
final class LosslessPdfOperations {
    interface Progress { void onProgress(int done, int total); }
    private interface Write { void write(StreamingImagePdf writer) throws IOException; }
    private LosslessPdfOperations() { }

    private static final class Source {
        final File file;
        final long length, modified;
        final int pages;
        Source(File file, ImagePdfReader reader) {
            this.file = file;
            length = reader.length;
            modified = reader.modified;
            pages = reader.pages.size();
        }
        void check(ImagePdfReader reader) throws IOException {
            if (length != reader.length || modified != reader.modified || pages != reader.pages.size()) {
                throw new IOException("A selected PDF changed. Choose the files again.");
            }
        }
    }

    static File merge(File directory, List<File> files, int maxPages, long maxBytes,
                       Progress progress) throws IOException {
        ImagePdfReader.cancelled();
        if (files == null || files.isEmpty()) throw new IOException("Choose PDF files to merge.");
        if (files.size() > maxPages) throw new IOException("A document can contain up to " + maxPages + " pages.");
        List<Source> sources = new ArrayList<>();
        int count = 0;
        for (File file : new ArrayList<>(files)) {
            try (ImagePdfReader reader = new ImagePdfReader(file, maxPages, maxBytes)) {
                count += reader.pages.size();
                if (count > maxPages) throw new IOException("A document can contain up to " + maxPages + " pages.");
                sources.add(new Source(file, reader));
            }
        }
        final int total = count;
        return create(directory, maxBytes, writer -> {
            int done = 0;
            for (Source source : sources) {
                try (ImagePdfReader reader = new ImagePdfReader(source.file, maxPages, maxBytes)) {
                    source.check(reader);
                    for (ImagePdfReader.Page page : reader.pages) {
                        append(writer, reader, page);
                        if (progress != null) progress.onProgress(++done, total);
                    }
                    reader.unchanged();
                }
            }
        });
    }

    static File extract(File directory, File source, int[] requested, int maxPages, long maxBytes,
                         Progress progress) throws IOException {
        ImagePdfReader.cancelled();
        if (requested == null || requested.length == 0) throw new IOException("Choose pages to extract.");
        if (requested.length > maxPages) throw new IOException("A document can contain up to " + maxPages + " pages.");
        int[] selection = requested.clone();
        Source original;
        try (ImagePdfReader reader = new ImagePdfReader(source, maxPages, maxBytes)) {
            boolean[] selected = new boolean[reader.pages.size()];
            for (int index : selection) {
                if (index < 0 || index >= selected.length) throw new IOException("Choose page numbers from 1 to " + selected.length + ".");
                if (selected[index]) throw new IOException("Each page can be selected once.");
                selected[index] = true;
            }
            original = new Source(source, reader);
        }
        return create(directory, maxBytes, writer -> {
            try (ImagePdfReader reader = new ImagePdfReader(source, maxPages, maxBytes)) {
                original.check(reader);
                int done = 0;
                for (int index : selection) {
                    append(writer, reader, reader.pages.get(index));
                    if (progress != null) progress.onProgress(++done, selection.length);
                }
                reader.unchanged();
            }
        });
    }

    private static void append(StreamingImagePdf writer, ImagePdfReader reader,
                               ImagePdfReader.Page page) throws IOException {
        ImagePdfReader.cancelled();
        try (InputStream jpeg = reader.image(page)) {
            writer.addPage(page.layout, jpeg, page.jpegLength, page.pixelWidth, page.pixelHeight);
        }
    }

    private static File create(File directory, long maxBytes, Write operation) throws IOException {
        ImagePdfReader.cancelled();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create the PDF export folder.");
        File partial = File.createTempFile("pages-", ".part", directory);
        File complete = new File(directory, partial.getName().replace(".part", ".pdf"));
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                StreamingImagePdf writer = new StreamingImagePdf(output, maxBytes);
                operation.write(writer);
                ImagePdfReader.cancelled();
                writer.finish();
                output.getFD().sync();
            }
            ImagePdfReader.cancelled();
            if (complete.exists() || partial.length() <= 0 || !partial.renameTo(complete)) {
                throw new IOException("Unable to finish writing the PDF.");
            }
            return complete;
        } catch (RuntimeException error) {
            throw new IOException("The PDF operation could not be completed.", error);
        } finally {
            partial.delete();
        }
    }
}
