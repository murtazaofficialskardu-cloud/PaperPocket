package com.paperpocket.app;

import java.io.IOException;
import java.util.Arrays;

/** Human page numbers are one-based; operation indices are zero-based. */
final class PdfPageSelection {
    private PdfPageSelection() { }

    static int[] parse(String input, int pageCount, int maxPages) throws IOException {
        if (pageCount <= 0 || pageCount > maxPages) throw new IOException("This PDF has an unsupported page count.");
        if (input == null || input.trim().isEmpty()) throw new IOException("Enter pages such as 1-3,5.");
        if (input.length() > 4096) throw new IOException("The page selection is too long.");
        boolean[] seen = new boolean[pageCount];
        int[] selected = new int[pageCount];
        int count = 0;
        for (String part : input.split(",", -1)) {
            String[] range = part.trim().split("\\s*-\\s*", -1);
            if (range.length < 1 || range.length > 2) throw new IOException("Enter pages such as 1-3,5.");
            int first = number(range[0], pageCount);
            int last = range.length == 2 ? number(range[1], pageCount) : first;
            if (last < first) throw new IOException("Enter ranges in ascending order, such as 1-3.");
            for (int page = first; page <= last; page++) {
                if (seen[page - 1]) throw new IOException("Each page can be selected once.");
                seen[page - 1] = true;
                selected[count++] = page - 1;
            }
        }
        return Arrays.copyOf(selected, count);
    }

    private static int number(String text, int pageCount) throws IOException {
        String value = text.trim();
        if (!value.matches("[0-9]{1,9}")) throw new IOException("Enter pages such as 1-3,5.");
        int page = Integer.parseInt(value);
        if (page < 1 || page > pageCount) throw new IOException("Choose page numbers from 1 to " + pageCount + ".");
        return page;
    }
}
