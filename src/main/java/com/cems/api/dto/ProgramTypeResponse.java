package com.cems.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A program type in the admin-managed library (spec §3.4), including its matrix row so the library
 * screen can show which needs the type addresses without a second request.
 */
public record ProgramTypeResponse(
        String id,
        String name,
        String description,
        String defaultDuration,
        boolean active,
        List<SectorResponse> sectors,
        List<NeedWeight> weights,
        Instant createdAt,
        Instant updatedAt) {

    /** One matrix cell: how strongly this type addresses a need category (0.00–5.00). */
    public record NeedWeight(String needCategoryId, String needCategoryName, BigDecimal weight) {
    }
}
