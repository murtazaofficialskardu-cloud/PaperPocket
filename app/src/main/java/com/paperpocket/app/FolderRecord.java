package com.paperpocket.app;

/** Private library organization; folder names never become filesystem paths. */
public final class FolderRecord {
    public final String id;
    public final String name;
    public final long createdAt;

    public FolderRecord(String id, String name, long createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }
}
