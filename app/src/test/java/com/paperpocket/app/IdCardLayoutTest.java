package com.paperpocket.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IdCardLayoutTest {
    @Test public void physicalCardFramesAreCenteredAndSeparatedOnA4() {
        IdCardLayout.Placement front = IdCardLayout.frame(0);
        IdCardLayout.Placement back = IdCardLayout.frame(1);
        assertEquals(85.60, front.width * 25.4 / 72, .001);
        assertEquals(53.98, front.height * 25.4 / 72, .001);
        assertEquals(595, IdCardLayout.PAGE_WIDTH);
        assertEquals(842, IdCardLayout.PAGE_HEIGHT);
        assertEquals((595 - front.width) / 2, front.left, .001);
        assertEquals(front.left, back.left, .001);
        assertEquals(48, back.top - front.top - front.height, .001);
        assertEquals(front.top, 842 - back.top - back.height, .001);
    }

    @Test public void wideAndPortraitImagesFitWithoutCroppingOrStretching() {
        for (int[] dimensions : new int[][]{{1600, 900}, {900, 1600}, {400, 400}, {2000, 100}}) {
            for (int side = 0; side < 2; side++) {
                IdCardLayout.Placement frame = IdCardLayout.frame(side);
                IdCardLayout.Placement image = IdCardLayout.fit(side, dimensions[0], dimensions[1]);
                assertEquals(dimensions[0] / (double) dimensions[1],
                        image.width / (double) image.height, .00001);
                assertTrue(image.width <= frame.width + .001);
                assertTrue(image.height <= frame.height + .001);
                assertEquals(frame.left + frame.width / 2,
                        image.left + image.width / 2, .001);
                assertEquals(frame.top + frame.height / 2,
                        image.top + image.height / 2, .001);
            }
        }
    }

    @Test public void everySupportedResolutionStaysBelowRasterMemoryCap() {
        for (int dpi = 72; dpi <= 300; dpi++) {
            int width = IdCardLayout.rasterWidth(dpi);
            int height = IdCardLayout.rasterHeight(dpi);
            assertTrue((long) width * height <= IdCardLayout.MAX_PIXELS);
            assertEquals(595 / 842.0, width / (double) height, .002);
        }
        assertEquals(2480, IdCardLayout.rasterWidth(300));
        assertEquals(3509, IdCardLayout.rasterHeight(300));
    }
}
