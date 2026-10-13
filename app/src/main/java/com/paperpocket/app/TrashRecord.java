package com.paperpocket.app;

import java.io.File;

/** A recoverable PDF retained in private storage until the user explicitly removes it. */
public final class TrashRecord {
    /** Opaque trash ID, used by restore and permanent deletion. */
    public String id;
    public String originalId;
    public String name;
    public int pageCount;
    public long createdAt;
    public long deletedAt;
    public long sizeBytes;
    public long modifiedAt;
    public File file;

    // Private filenames are validated by PdfStore; callers never supply a restore path.
    String originalFilename;
    String restoreFilename;
}
