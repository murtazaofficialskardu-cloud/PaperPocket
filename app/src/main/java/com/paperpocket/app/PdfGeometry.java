package com.paperpocket.app;

/** Pure page layout and naming rules shared by export and storage. */
public final class PdfGeometry {
    private PdfGeometry() { }

    public static final class Layout {
        public final int pageWidth;
        public final int pageHeight;
        public final float left;
        public final float top;
        public final float imageWidth;
        public final float imageHeight;

        private Layout(int pageWidth, int pageHeight, float left, float top,
                       float imageWidth, float imageHeight) {
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            this.left = left;
            this.top = top;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }
    }

    /** A rendered PDF page keeps its original physical size and fills the page. */
    public static Layout fullPage(int widthPoints, int heightPoints) {
        if (widthPoints <= 0 || heightPoints <= 0) {
            throw new IllegalArgumentException("Page dimensions must be positive.");
        }
        return new Layout(widthPoints, heightPoints, 0, 0, widthPoints, heightPoints);
    }

    /** Measures in PDF points, keeping the complete image and its aspect ratio. */
    public static Layout layout(int width, int height, String paper, boolean margin) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive.");
        }
        int pageWidth;
        int pageHeight;
        if ("AUTO".equalsIgnoreCase(paper)) {
            double scale = 842.0 / Math.max(width, height);
            pageWidth = Math.max(1, (int) Math.round(width * scale));
            pageHeight = Math.max(1, (int) Math.round(height * scale));
        } else {
            boolean letter = "LETTER".equalsIgnoreCase(paper);
            int shortSide = letter ? 612 : 595;
            int longSide = letter ? 792 : 842;
            pageWidth = width > height ? longSide : shortSide;
            pageHeight = width > height ? shortSide : longSide;
        }
        float inset = margin ? Math.min(24f, Math.min(pageWidth, pageHeight) / 8f) : 0;
        float availableWidth = pageWidth - 2 * inset;
        float availableHeight = pageHeight - 2 * inset;
        float scale = Math.min(availableWidth / width, availableHeight / height);
        float drawnWidth = width * scale;
        float drawnHeight = height * scale;
        return new Layout(pageWidth, pageHeight, (pageWidth - drawnWidth) / 2f,
                (pageHeight - drawnHeight) / 2f, drawnWidth, drawnHeight);
    }

    /** A safe, human-readable name with exactly one PDF suffix. */
    public static String normalizeName(String requested) {
        String name = requested == null ? "" : requested.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        while (name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4).trim();
        }
        while (name.startsWith(".")) name = name.substring(1).trim();
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1).trim();
        if (name.isEmpty()) name = "Document";
        // Keep space for the extension and do not split a UTF-16 surrogate pair.
        if (name.length() > 100) {
            int length = Character.isHighSurrogate(name.charAt(99)) ? 99 : 100;
            name = name.substring(0, length).trim();
        }
        return name + ".pdf";
    }
}
