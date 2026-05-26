package com.cems.api.service;

import com.cems.api.dto.UserResponse;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class UserPdfExportService {

    private static final Charset PDF_CHARSET = StandardCharsets.ISO_8859_1;
    private static final double PAGE_WIDTH = 842;
    private static final double PAGE_HEIGHT = 595;
    private static final double MARGIN = 36;
    private static final double TABLE_TOP = 488;
    private static final double ROW_HEIGHT = 24;
    private static final int ROWS_PER_PAGE = 16;
    private static final double[] COLUMN_WIDTHS = {150, 210, 100, 70, 105, 135};
    private static final String[] HEADERS = {"Name", "Email", "Role", "Status", "Contact", "Created"};
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    public byte[] buildUsersPdf(List<UserResponse> users) {
        List<String> pageStreams = buildPageStreams(users);
        int pageCount = pageStreams.size();
        int objectCount = 4 + (pageCount * 2);
        byte[][] objectBodies = new byte[objectCount + 1][];

        objectBodies[1] = toPdfBytes("<< /Type /Catalog /Pages 2 0 R >>");
        objectBodies[3] = toPdfBytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        objectBodies[4] = toPdfBytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>");

        StringBuilder kids = new StringBuilder();
        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            int pageObjectId = pageObjectId(pageIndex);
            int contentObjectId = contentObjectId(pageIndex);
            kids.append(pageObjectId).append(" 0 R ");

            objectBodies[pageObjectId] = toPdfBytes(String.format(Locale.ROOT,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.0f %.0f] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents %d 0 R >>",
                    PAGE_WIDTH,
                    PAGE_HEIGHT,
                    contentObjectId));

            byte[] contentBytes = toPdfBytes(pageStreams.get(pageIndex));
            objectBodies[contentObjectId] = concat(
                    toPdfBytes("<< /Length " + contentBytes.length + " >>\nstream\n"),
                    contentBytes,
                    toPdfBytes("\nendstream"));
        }

        objectBodies[2] = toPdfBytes("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageCount + " >>");

        try {
            return writePdfDocument(objectBodies, objectCount);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to generate users PDF.", ex);
        }
    }

    private List<String> buildPageStreams(List<UserResponse> users) {
        int pageCount = Math.max(1, (int) Math.ceil(users.size() / (double) ROWS_PER_PAGE));

        return java.util.stream.IntStream.range(0, pageCount)
                .mapToObj(pageIndex -> buildPageStream(users, pageIndex, pageCount))
                .toList();
    }

    private String buildPageStream(List<UserResponse> users, int pageIndex, int pageCount) {
        StringBuilder stream = new StringBuilder();
        drawPageBackground(stream);
        drawTitle(stream, users.size(), pageIndex, pageCount);
        drawTableHeader(stream);

        int startIndex = pageIndex * ROWS_PER_PAGE;
        int endIndex = Math.min(startIndex + ROWS_PER_PAGE, users.size());

        if (users.isEmpty()) {
            drawText(stream, "F1", 10, MARGIN + 10, TABLE_TOP - 42, "No user records found.");
            return stream.toString();
        }

        for (int userIndex = startIndex; userIndex < endIndex; userIndex++) {
            drawUserRow(stream, users.get(userIndex), userIndex - startIndex);
        }

        return stream.toString();
    }

    private void drawPageBackground(StringBuilder stream) {
        stream.append("q\n")
                .append("1 1 1 rg\n")
                .append("0 0 ").append(PAGE_WIDTH).append(" ").append(PAGE_HEIGHT).append(" re f\n")
                .append("Q\n");
    }

    private void drawTitle(StringBuilder stream, int totalUsers, int pageIndex, int pageCount) {
        drawText(stream, "F2", 18, MARGIN, PAGE_HEIGHT - 44, "CEMS User List");
        drawText(stream, "F1", 9, MARGIN, PAGE_HEIGHT - 62,
                "Generated " + DATE_FORMATTER.format(Instant.now()) + " | " + totalUsers + " user record"
                        + (totalUsers == 1 ? "" : "s"));
        drawText(stream, "F1", 9, PAGE_WIDTH - 112, PAGE_HEIGHT - 62,
                "Page " + (pageIndex + 1) + " of " + pageCount);
    }

    private void drawTableHeader(StringBuilder stream) {
        double tableWidth = java.util.Arrays.stream(COLUMN_WIDTHS).sum();
        drawFilledRectangle(stream, MARGIN, TABLE_TOP - ROW_HEIGHT, tableWidth, ROW_HEIGHT, "0.93 0.97 0.92 rg");
        drawStrokedRectangle(stream, MARGIN, TABLE_TOP - ROW_HEIGHT, tableWidth, ROW_HEIGHT, "0.72 0.81 0.70 RG");

        double x = MARGIN;
        for (int index = 0; index < HEADERS.length; index++) {
            drawText(stream, "F2", 8.5, x + 6, TABLE_TOP - 15, HEADERS[index]);
            x += COLUMN_WIDTHS[index];
        }
    }

    private void drawUserRow(StringBuilder stream, UserResponse user, int rowIndex) {
        double tableWidth = java.util.Arrays.stream(COLUMN_WIDTHS).sum();
        double rowTop = TABLE_TOP - ROW_HEIGHT - (rowIndex * ROW_HEIGHT);
        String fillColor = rowIndex % 2 == 0 ? "1 1 1 rg" : "0.98 0.99 0.97 rg";
        drawFilledRectangle(stream, MARGIN, rowTop - ROW_HEIGHT, tableWidth, ROW_HEIGHT, fillColor);
        drawStrokedRectangle(stream, MARGIN, rowTop - ROW_HEIGHT, tableWidth, ROW_HEIGHT, "0.88 0.92 0.86 RG");

        String[] values = {
                truncate(fullName(user), 27),
                truncate(safe(user.getEmail()), 36),
                truncate(formatRole(user), 16),
                user.isActive() ? "Active" : "Inactive",
                truncate(safe(user.getContactNumber()), 18),
                formatInstant(user.getCreatedAt())
        };

        double x = MARGIN;
        for (int index = 0; index < values.length; index++) {
            drawText(stream, "F1", 8, x + 6, rowTop - 15, values[index]);
            x += COLUMN_WIDTHS[index];
        }
    }

    private void drawText(StringBuilder stream, String fontName, double fontSize, double x, double y, String value) {
        stream.append("BT /")
                .append(fontName)
                .append(" ")
                .append(formatNumber(fontSize))
                .append(" Tf ")
                .append(formatNumber(x))
                .append(" ")
                .append(formatNumber(y))
                .append(" Td ")
                .append(pdfString(value))
                .append(" Tj ET\n");
    }

    private void drawFilledRectangle(StringBuilder stream, double x, double y, double width, double height, String colorCommand) {
        stream.append("q\n")
                .append(colorCommand)
                .append("\n")
                .append(formatNumber(x))
                .append(" ")
                .append(formatNumber(y))
                .append(" ")
                .append(formatNumber(width))
                .append(" ")
                .append(formatNumber(height))
                .append(" re f\n")
                .append("Q\n");
    }

    private void drawStrokedRectangle(StringBuilder stream, double x, double y, double width, double height, String colorCommand) {
        stream.append("q\n")
                .append(colorCommand)
                .append("\n0.6 w\n")
                .append(formatNumber(x))
                .append(" ")
                .append(formatNumber(y))
                .append(" ")
                .append(formatNumber(width))
                .append(" ")
                .append(formatNumber(height))
                .append(" re S\n")
                .append("Q\n");
    }

    private byte[] writePdfDocument(byte[][] objectBodies, int objectCount) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long[] offsets = new long[objectCount + 1];

        write(output, "%PDF-1.4\n");
        for (int objectId = 1; objectId <= objectCount; objectId++) {
            offsets[objectId] = output.size();
            write(output, objectId + " 0 obj\n");
            output.write(objectBodies[objectId]);
            write(output, "\nendobj\n");
        }

        long xrefOffset = output.size();
        write(output, "xref\n0 " + (objectCount + 1) + "\n");
        write(output, "0000000000 65535 f \n");
        for (int objectId = 1; objectId <= objectCount; objectId++) {
            write(output, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[objectId]));
        }
        write(output, "trailer\n<< /Size " + (objectCount + 1) + " /Root 1 0 R >>\n");
        write(output, "startxref\n" + xrefOffset + "\n%%EOF\n");
        return output.toByteArray();
    }

    private int pageObjectId(int pageIndex) {
        return 5 + (pageIndex * 2);
    }

    private int contentObjectId(int pageIndex) {
        return pageObjectId(pageIndex) + 1;
    }

    private String fullName(UserResponse user) {
        return String.join(" ",
                safe(user.getFirstName()),
                safe(user.getMiddleName()),
                safe(user.getLastName())).replaceAll("\\s+", " ").trim();
    }

    private String formatRole(UserResponse user) {
        Set<String> roles = user.getRoles() == null ? Set.of() : user.getRoles();
        if (roles.contains(UserManagementService.ROLE_SUPER_ADMIN)) {
            return "Super Admin";
        }
        if (roles.contains(UserManagementService.ROLE_ADMIN)) {
            return "Admin";
        }
        return "User";
    }

    private String formatInstant(Instant instant) {
        return instant == null ? "-" : DATE_FORMATTER.format(instant);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String pdfString(String value) {
        return "(" + value
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("\r", " ")
                .replace("\n", " ") + ")";
    }

    private String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private byte[] concat(byte[]... chunks) {
        int totalLength = 0;
        for (byte[] chunk : chunks) {
            totalLength += chunk.length;
        }

        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(toPdfBytes(value));
    }

    private byte[] toPdfBytes(String value) {
        return value.getBytes(PDF_CHARSET);
    }
}
