package com.cems.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hand-rolled CSV cell splitter behind attendance import.
 *
 * <p>It is hand-rolled rather than delegated to a parser library because the spec defines a fixed
 * four-column shape and the project carries no CSV dependency. That trade is only defensible if the
 * quoting rules actually hold, which is what these cases pin — a name like {@code "Santos, Maria"}
 * must not silently split into two cells and shift every later column, quietly turning a sex into
 * an age.
 */
class AttendanceCsvParsingTest {

    @Test
    @DisplayName("a plain row splits on commas")
    void splitsPlainRow() {
        assertArrayEquals(new String[] { "Maria Santos", "female", "34", "Farmers" },
                AttendanceService.splitCsv("Maria Santos,female,34,Farmers"));
    }

    @Test
    @DisplayName("a quoted cell may contain a comma")
    void quotedCellKeepsItsComma() {
        String[] cells = AttendanceService.splitCsv("\"Santos, Maria\",female,34,");
        assertEquals(4, cells.length);
        assertEquals("Santos, Maria", cells[0]);
        assertEquals("female", cells[1], "the sex must not be shifted by the embedded comma");
    }

    @Test
    @DisplayName("a doubled quote inside a quoted cell is one literal quote")
    void escapedQuoteIsUnwrapped() {
        String[] cells = AttendanceService.splitCsv("\"Maria \"\"Ria\"\" Santos\",female,,");
        assertEquals("Maria \"Ria\" Santos", cells[0]);
        assertEquals("female", cells[1]);
    }

    @Test
    @DisplayName("trailing empty cells are preserved so column positions stay stable")
    void trailingEmptyCellsSurvive() {
        assertArrayEquals(new String[] { "Maria Santos", "female", "", "" },
                AttendanceService.splitCsv("Maria Santos,female,,"));
    }

    @ParameterizedTest
    @DisplayName("rows with missing trailing columns still expose the leading ones")
    @CsvSource({
            "'Maria Santos,female', 2",
            "'Maria Santos', 1",
            "'Maria Santos,female,34', 3",
    })
    void shortRowsAreNotPadded(String line, int expectedCells) {
        assertEquals(expectedCells, AttendanceService.splitCsv(line).length);
    }
}
