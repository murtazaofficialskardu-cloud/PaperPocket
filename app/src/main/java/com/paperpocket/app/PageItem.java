package com.paperpocket.app;

import java.util.UUID;

/** An imported page, with reversible edits applied when previewing or exporting. */
public final class PageItem {
    public enum Filter { ORIGINAL, ENHANCED, BLACK_WHITE }

    public String id;
    public String path;
    public int rotation = 0;
    public Filter filter = Filter.ORIGINAL;

    public PageItem(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("A page needs an image path.");
        }
        this.id = UUID.randomUUID().toString();
        this.path = path;
    }

    /** Snapshot edits before starting a background export. */
    public PageItem copy() {
        PageItem copy = new PageItem(path);
        copy.id = id;
        copy.rotation = rotation;
        copy.filter = filter;
        return copy;
    }
}
