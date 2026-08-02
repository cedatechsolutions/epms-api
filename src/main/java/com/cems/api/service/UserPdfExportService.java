package com.cems.api.service;

import com.cems.api.dto.UserResponse;
import com.cems.api.security.RoleName;
import com.cems.api.util.PdfDocumentBuilder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Renders the user list as a landscape A4 PDF, using the shared {@link PdfDocumentBuilder}. */
@Service
public class UserPdfExportService {

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
        PdfDocumentBuilder document = new PdfDocumentBuilder(PAGE_WIDTH, PAGE_HEIGHT);
        int pageCount = Math.max(1, (int) Math.ceil(users.size() / (double) ROWS_PER_PAGE));

        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            PdfDocumentBuilder.Page page = document.addPage();
            drawTitle(page, users.size(), pageIndex, pageCount);
            drawTableHeader(page);

            if (users.isEmpty()) {
                page.text(PdfDocumentBuilder.FONT_REGULAR, 10, MARGIN + 10, TABLE_TOP - 42,
                        "No user records found.");
                continue;
            }

            int start = pageIndex * ROWS_PER_PAGE;
            int end = Math.min(start + ROWS_PER_PAGE, users.size());
            for (int index = start; index < end; index++) {
                drawUserRow(page, users.get(index), index - start);
            }
        }

        return document.build();
    }

    private void drawTitle(PdfDocumentBuilder.Page page, int totalUsers, int pageIndex, int pageCount) {
        page.text(PdfDocumentBuilder.FONT_BOLD, 18, MARGIN, PAGE_HEIGHT - 44, "CEMS User List");
        page.text(PdfDocumentBuilder.FONT_REGULAR, 9, MARGIN, PAGE_HEIGHT - 62,
                "Generated " + DATE_FORMATTER.format(Instant.now()) + " | " + totalUsers + " user record"
                        + (totalUsers == 1 ? "" : "s"));
        page.text(PdfDocumentBuilder.FONT_REGULAR, 9, PAGE_WIDTH - 112, PAGE_HEIGHT - 62,
                "Page " + (pageIndex + 1) + " of " + pageCount);
    }

    private void drawTableHeader(PdfDocumentBuilder.Page page) {
        double tableWidth = Arrays.stream(COLUMN_WIDTHS).sum();
        page.fillRect(MARGIN, TABLE_TOP - ROW_HEIGHT, tableWidth, ROW_HEIGHT, "0.93 0.97 0.92 rg");
        page.strokeRect(MARGIN, TABLE_TOP - ROW_HEIGHT, tableWidth, ROW_HEIGHT, "0.72 0.81 0.70 RG");

        double x = MARGIN;
        for (int index = 0; index < HEADERS.length; index++) {
            page.text(PdfDocumentBuilder.FONT_BOLD, 8.5, x + 6, TABLE_TOP - 15, HEADERS[index]);
            x += COLUMN_WIDTHS[index];
        }
    }

    private void drawUserRow(PdfDocumentBuilder.Page page, UserResponse user, int rowIndex) {
        double tableWidth = Arrays.stream(COLUMN_WIDTHS).sum();
        double rowTop = TABLE_TOP - ROW_HEIGHT - (rowIndex * ROW_HEIGHT);
        String fill = rowIndex % 2 == 0 ? "1 1 1 rg" : "0.98 0.99 0.97 rg";
        page.fillRect(MARGIN, rowTop - ROW_HEIGHT, tableWidth, ROW_HEIGHT, fill);
        page.strokeRect(MARGIN, rowTop - ROW_HEIGHT, tableWidth, ROW_HEIGHT, "0.88 0.92 0.86 RG");

        String[] values = {
                PdfDocumentBuilder.truncate(fullName(user), 27),
                PdfDocumentBuilder.truncate(safe(user.getEmail()), 36),
                PdfDocumentBuilder.truncate(formatRole(user), 16),
                user.isActive() ? "Active" : "Inactive",
                PdfDocumentBuilder.truncate(safe(user.getContactNumber()), 18),
                formatInstant(user.getCreatedAt())
        };

        double x = MARGIN;
        for (int index = 0; index < values.length; index++) {
            page.text(PdfDocumentBuilder.FONT_REGULAR, 8, x + 6, rowTop - 15, values[index]);
            x += COLUMN_WIDTHS[index];
        }
    }

    private String fullName(UserResponse user) {
        return String.join(" ",
                safe(user.getFirstName()),
                safe(user.getMiddleName()),
                safe(user.getLastName())).replaceAll("\\s+", " ").trim();
    }

    private String formatRole(UserResponse user) {
        Set<String> roles = user.getRoles() == null ? Set.of() : user.getRoles();
        return roles.stream()
                .map(RoleName::displayNameForCode)
                .sorted()
                .findFirst()
                .orElse("-");
    }

    private String formatInstant(Instant instant) {
        return instant == null ? "-" : DATE_FORMATTER.format(instant);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
