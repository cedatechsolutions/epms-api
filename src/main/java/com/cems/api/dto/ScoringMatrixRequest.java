package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bulk matrix update (spec Module 4 §4). Only the supplied cells are written; a weight of 0 removes
 * the row, keeping the stored matrix sparse.
 *
 * <p>Edits affect future scoring runs only — existing recommendations keep the breakdown they were
 * generated with.
 */
public record ScoringMatrixRequest(@NotNull List<MatrixCell> cells) {

    public record MatrixCell(
            @NotBlank String programTypeId,
            @NotBlank String needCategoryId,
            @NotNull BigDecimal weight) {
    }
}
