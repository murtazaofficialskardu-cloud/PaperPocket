package com.paperpocket.app;

import java.io.File;

/** Saved PDF metadata. Display names are separate from immutable on-disk IDs. */
public final class PdfRecord {
    public String id;
    public String name;
    public int pageCount;
    public long createdAt;
    public long sizeBytes;
    /** File timestamp observed when the PDF was last verified. */
    public long modifiedAt;
    public File file;
    public File thumbnailFile;

    public PdfRecord() { }

    public PdfRecord(String id, String name, int pageCount, long createdAt, File file) {
        this.id = id;
        this.name = name;
        this.pageCount = pageCount;
        this.createdAt = createdAt;
        this.file = file;
        this.sizeBytes = file == null ? 0 : file.length();
        this.modifiedAt = file == null ? 0 : file.lastModified();
    }
}
