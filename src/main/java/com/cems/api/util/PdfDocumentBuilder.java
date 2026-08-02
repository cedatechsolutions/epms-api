package com.cems.api.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal PDF writer: assembles pages of text and rectangles into a valid PDF 1.4 byte stream.
 *
 * <p>The project deliberately has no PDF library — this is the shared primitive behind every
 * server-side PDF (user list, assessment summary). Fonts are the two PDF base-14 fonts, referenced
 * from page content as {@code F1} (Helvetica) and {@code F2} (Helvetica-Bold).
 */
public class PdfDocumentBuilder {

    private static final Charset PDF_CHARSET = StandardCharsets.ISO_8859_1;

    /** Regular font resource name. */
    public static final String FONT_REGULAR = "F1";
    /** Bold font resource name. */
    public static final String FONT_BOLD = "F2";

    private final double pageWidth;
    private final double pageHeight;
    private final List<Page> pages = new ArrayList<>();

    public PdfDocumentBuilder(double pageWidth, double pageHeight) {
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
    }

    public double pageWidth() {
        return pageWidth;
    }

    public double pageHeight() {
        return pageHeight;
    }

    public Page addPage() {
        Page page = new Page();
        page.fillRect(0, 0, pageWidth, pageHeight, "1 1 1 rg");
        pages.add(page);
        return page;
    }

    /** A single page's content stream. Coordinates are PDF user space (origin bottom-left). */
    public static final class Page {

        private final StringBuilder stream = new StringBuilder();

        private Page() {
        }

        public void text(String font, double size, double x, double y, String value) {
            stream.append("BT /").append(font).append(' ').append(number(size)).append(" Tf ")
                    .append(number(x)).append(' ').append(number(y)).append(" Td ")
                    .append(escape(value)).append(" Tj ET\n");
        }

        public void fillRect(double x, double y, double width, double height, String colorCommand) {
            stream.append("q\n").append(colorCommand).append('\n')
                    .append(number(x)).append(' ').append(number(y)).append(' ')
                    .append(number(width)).append(' ').append(number(height)).append(" re f\n")
                    .append("Q\n");
        }

        public void strokeRect(double x, double y, double width, double height, String colorCommand) {
            stream.append("q\n").append(colorCommand).append("\n0.6 w\n")
                    .append(number(x)).append(' ').append(number(y)).append(' ')
                    .append(number(width)).append(' ').append(number(height)).append(" re S\n")
                    .append("Q\n");
        }

        private static String escape(String value) {
            return "(" + (value == null ? "" : value)
                    .replace("\\", "\\\\")
                    .replace("(", "\\(")
                    .replace(")", "\\)")
                    .replace("\r", " ")
                    .replace("\n", " ") + ")";
        }

        private static String number(double value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
    }

    /** Assembles the pages into a complete PDF document. */
    public byte[] build() {
        if (pages.isEmpty()) {
            addPage();
        }

        int pageCount = pages.size();
        int objectCount = 4 + (pageCount * 2);
        byte[][] objects = new byte[objectCount + 1][];

        objects[1] = bytes("<< /Type /Catalog /Pages 2 0 R >>");
        objects[3] = bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        objects[4] = bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>");

        StringBuilder kids = new StringBuilder();
        for (int index = 0; index < pageCount; index++) {
            int pageId = 5 + (index * 2);
            int contentId = pageId + 1;
            kids.append(pageId).append(" 0 R ");

            objects[pageId] = bytes(String.format(Locale.ROOT,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.0f %.0f] "
                            + "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents %d 0 R >>",
                    pageWidth, pageHeight, contentId));

            byte[] content = bytes(pages.get(index).stream.toString());
            objects[contentId] = concat(
                    bytes("<< /Length " + content.length + " >>\nstream\n"),
                    content,
                    bytes("\nendstream"));
        }

        objects[2] = bytes("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageCount + " >>");

        try {
            return write(objects, objectCount);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to generate PDF.", ex);
        }
    }

    private byte[] write(byte[][] objects, int objectCount) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long[] offsets = new long[objectCount + 1];

        output.write(bytes("%PDF-1.4\n"));
        for (int id = 1; id <= objectCount; id++) {
            offsets[id] = output.size();
            output.write(bytes(id + " 0 obj\n"));
            output.write(objects[id]);
            output.write(bytes("\nendobj\n"));
        }

        long xrefOffset = output.size();
        output.write(bytes("xref\n0 " + (objectCount + 1) + "\n"));
        output.write(bytes("0000000000 65535 f \n"));
        for (int id = 1; id <= objectCount; id++) {
            output.write(bytes(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id])));
        }
        output.write(bytes("trailer\n<< /Size " + (objectCount + 1) + " /Root 1 0 R >>\n"));
        output.write(bytes("startxref\n" + xrefOffset + "\n%%EOF\n"));
        return output.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(PDF_CHARSET);
    }

    private static byte[] concat(byte[]... chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    /** Truncates a value to fit a fixed-width table cell. */
    public static String truncate(String value, int maxLength) {
        String safe = value == null ? "" : value;
        if (safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
