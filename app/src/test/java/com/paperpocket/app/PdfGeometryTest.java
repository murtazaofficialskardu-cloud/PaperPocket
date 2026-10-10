package com.paperpocket.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Export rules that should stay stable independently of Android rendering. */
public class PdfGeometryTest {
    @Test public void portraitUsesA4AndPreservesAspectRatio() {
        PdfGeometry.Layout layout = PdfGeometry.layout(1200, 1800, "A4", true);
        assertEquals(595, layout.pageWidth);
        assertEquals(842, layout.pageHeight);
        assertEquals(1200f / 1800f, layout.imageWidth / layout.imageHeight, .0001f);
        assertTrue(layout.left >= 24f);
        assertTrue(layout.top >= 24f);
        assertTrue(layout.left + layout.imageWidth <= 571.001f);
        assertTrue(layout.top + layout.imageHeight <= 818.001f);
    }

    @Test public void landscapeUsesLandscapeLetter() {
        PdfGeometry.Layout layout = PdfGeometry.layout(3000, 2000, "LETTER", false);
        assertEquals(792, layout.pageWidth);
        assertEquals(612, layout.pageHeight);
        assertEquals(792f, layout.imageWidth, .001f);
        assertEquals(528f, layout.imageHeight, .001f);
        assertEquals(42f, layout.top, .001f);
    }

    @Test public void automaticPageFollowsImageRatio() {
        PdfGeometry.Layout layout = PdfGeometry.layout(2000, 1000, "AUTO", false);
        assertEquals(842, layout.pageWidth);
        assertEquals(421, layout.pageHeight);
        assertEquals(0f, layout.left, .001f);
        assertEquals(0f, layout.top, .001f);
    }

    @Test public void marginsRemainValidForVeryNarrowPages() {
        PdfGeometry.Layout layout = PdfGeometry.layout(1, 10000, "AUTO", true);
        assertTrue(layout.imageWidth > 0);
        assertTrue(layout.imageHeight > 0);
        assertTrue(layout.left >= 0);
        assertTrue(layout.top >= 0);
        assertTrue(layout.left + layout.imageWidth <= layout.pageWidth);
        assertTrue(layout.top + layout.imageHeight <= layout.pageHeight);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyImage() {
        PdfGeometry.layout(0, 100, "A4", true);
    }

    @Test public void namesHaveExactlyOneSuffix() {
        assertEquals("Invoice.pdf", PdfGeometry.normalizeName("  Invoice.PDF.pdf  "));
        assertEquals("Document.pdf", PdfGeometry.normalizeName(" .pdf.pdf "));
        assertEquals("Document.pdf", PdfGeometry.normalizeName(null));
    }

    @Test public void namesRemovePathAndControlCharacters() {
        String name = PdfGeometry.normalizeName("../Tax\\2026:receipt\nfinal");
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
        assertFalse(name.contains(":"));
        assertFalse(name.contains("\n"));
        assertFalse(name.startsWith("."));
        assertEquals("Tax 2026 receipt final.pdf", name);
    }

    @Test public void truncationDoesNotSplitUnicode() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 99; i++) name.append('a');
        name.append("\uD83D\uDCC4extra");
        String result = PdfGeometry.normalizeName(name.toString());
        assertEquals(103, result.length());
        assertFalse(Character.isHighSurrogate(result.charAt(98)));
        assertTrue(result.endsWith(".pdf"));
    }
}
