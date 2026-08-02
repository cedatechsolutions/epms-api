package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

/**
 * Create/update payload for a survey question (spec Module 3 §1). {@code weight} range (0.5–5.0),
 * option requirements for choice types, and {@code questionType} validity are enforced in the
 * service. {@code orderIndex} is optional on create (appended to the end when null).
 */
public record QuestionRequest(
        @NotBlank String questionText,
        @NotBlank String questionType,
        List<QuestionOption> options,
        BigDecimal weight,
        String needCategoryId,
        Boolean required,
        Integer orderIndex) {
}
