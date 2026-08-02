package com.cems.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The scoring matrix as a grid (spec Module 4 §4): program types down, need categories across.
 *
 * <p>The matrix is stored sparsely — an absent row means weight 0 — so {@code weights} is filled in
 * for every category here, letting the admin grid render without null-handling per cell.
 */
public record ScoringMatrixResponse(
        List<NeedCategoryResponse> categories,
        List<MatrixRow> rows) {

    /** {@code weights} is keyed by need category id and always covers every category above. */
    public record MatrixRow(
            String programTypeId,
            String programTypeName,
            boolean active,
            Map<String, BigDecimal> weights) {
    }
}
