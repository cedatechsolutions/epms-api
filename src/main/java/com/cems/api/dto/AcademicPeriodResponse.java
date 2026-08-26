package com.cems.api.dto;

import com.cems.api.entity.AcademicPeriod;

import java.time.LocalDate;

/**
 * One option in the dashboard's period selector (spec Module 6 §1).
 *
 * <p>{@code current} is <em>computed</em> — it is true for the single period the server resolved as
 * today's, not a copy of the {@code is_current} column. The client marks its default selection from
 * this flag, so exactly one row in a response ever carries it.
 */
public record AcademicPeriodResponse(
        String id,
        String label,
        LocalDate startsOn,
        LocalDate endsOn,
        boolean current) {

    public static AcademicPeriodResponse fromEntity(AcademicPeriod period, boolean current) {
        return new AcademicPeriodResponse(
                period.getId(),
                period.getLabel(),
                period.getStartsOn(),
                period.getEndsOn(),
                current);
    }
}
