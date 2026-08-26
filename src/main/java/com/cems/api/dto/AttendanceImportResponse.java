package com.cems.api.dto;

import java.util.List;

/**
 * Result of a CSV attendance import (spec Module 5b AC 4: "CSV rows with invalid sex values are
 * rejected with row-level error report").
 *
 * <p><strong>The import is partial by design.</strong> Valid rows are saved and invalid ones are
 * reported; a single bad sex value in row 40 does not discard the 39 good rows above it. That is the
 * behaviour a coordinator typing up a paper sign-in sheet needs — the alternative, all-or-nothing,
 * makes them re-upload the whole file to fix one typo. The caller sees exactly what landed and what
 * did not.
 *
 * @param imported how many rows were persisted
 * @param skipped  how many were rejected
 * @param errors   one entry per rejected row, carrying the 1-based line number from the file
 * @param totals   the activity's running Total/F/M *after* the import
 */
public record AttendanceImportResponse(
        int imported,
        int skipped,
        List<RowError> errors,
        SexSplitResponse totals) {

    /**
     * @param line    1-based line number in the uploaded file, counting the header — so it matches
     *                what the user sees in their spreadsheet
     * @param message why the row was rejected
     * @param content the raw line, echoed back so the user can find it without opening the file
     */
    public record RowError(int line, String message, String content) {
    }
}
