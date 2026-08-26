package com.cems.api.dto;

import com.cems.api.entity.Evaluation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An encoded evaluation summary (spec Module 5b §5). {@code filePath} is deliberately absent —
 * the optional scanned instrument is fetched through the authorized download route by id.
 *
 * <p>{@code unspecifiedCount} is surfaced explicitly so the client can say "3 respondents did not
 * state a sex" in words instead of rendering a third bucket in the meter (UI guidelines §6.11).
 */
public record EvaluationResponse(
        String id,
        String programActivityId,
        String evalType,
        int respondentCount,
        int femaleCount,
        int maleCount,
        int unspecifiedCount,
        BigDecimal avgRating,
        String notes,
        String originalFilename,
        Long sizeBytes,
        String encodedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static EvaluationResponse fromEntity(Evaluation evaluation) {
        return new EvaluationResponse(
                evaluation.getId(),
                evaluation.getProgramActivity() == null ? null : evaluation.getProgramActivity().getId(),
                evaluation.getEvalType(),
                evaluation.getRespondentCount(),
                evaluation.getFemaleCount(),
                evaluation.getMaleCount(),
                evaluation.getUnspecifiedCount(),
                evaluation.getAvgRating(),
                evaluation.getNotes(),
                evaluation.getOriginalFilename(),
                evaluation.getSizeBytes(),
                evaluation.getEncodedBy(),
                evaluation.getCreatedAt(),
                evaluation.getUpdatedAt());
    }
}
