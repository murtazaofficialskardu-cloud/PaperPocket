package com.paperpocket.app;

/** A4 sheet geometry in PDF points; an ID-1 card is 85.60 by 53.98 millimetres. */
final class IdCardLayout {
    static final int PAGE_WIDTH = 595;
    static final int PAGE_HEIGHT = 842;
    static final float CARD_WIDTH = 85.60f * 72f / 25.4f;
    static final float CARD_HEIGHT = 53.98f * 72f / 25.4f;
    static final float GAP = 48f;
    static final long MAX_PIXELS = 8_800_000L;

    private IdCardLayout() { }

    static final class Placement {
        final float left;
        final float top;
        final float width;
        final float height;

        Placement(float left, float top, float width, float height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    static Placement frame(int side) {
        if (side < 0 || side > 1) throw new IllegalArgumentException("An ID card has two sides.");
        float top = (PAGE_HEIGHT - 2 * CARD_HEIGHT - GAP) / 2f;
        return new Placement((PAGE_WIDTH - CARD_WIDTH) / 2f,
                top + side * (CARD_HEIGHT + GAP), CARD_WIDTH, CARD_HEIGHT);
    }

    /** Letterbox each side, including portrait crops, without changing its aspect ratio. */
    static Placement fit(int side, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid card image.");
        Placement frame = frame(side);
        float scale = Math.min(frame.width / width, frame.height / height);
        float fittedWidth = width * scale;
        float fittedHeight = height * scale;
        return new Placement(frame.left + (frame.width - fittedWidth) / 2f,
                frame.top + (frame.height - fittedHeight) / 2f, fittedWidth, fittedHeight);
    }

    static int rasterWidth(int dpi) { return pixels(PAGE_WIDTH, dpi); }
    static int rasterHeight(int dpi) { return pixels(PAGE_HEIGHT, dpi); }

    private static int pixels(int points, int dpi) {
        if (dpi < 72 || dpi > 300) throw new IllegalArgumentException("Invalid card resolution.");
        return (int) Math.ceil(points * dpi / 72.0);
    }
}
