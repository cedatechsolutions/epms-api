package com.cems.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Encoded pre/post evaluation summary for one activity (spec Module 5b §5).
 *
 * <p>The cross-field rule — {@code femaleCount + maleCount <= respondentCount} — is enforced in the
 * service (422 with both field names) rather than by annotation, so the message can name the actual
 * numbers. An annotation here could only mark the record invalid as a whole.
 */
public record EvaluationRequest(
        @NotBlank String evalType,
        @PositiveOrZero Integer respondentCount,
        @PositiveOrZero Integer femaleCount,
        @PositiveOrZero Integer maleCount,
        @DecimalMin("1.00") @DecimalMax("5.00") BigDecimal avgRating,
        String notes) {
}
