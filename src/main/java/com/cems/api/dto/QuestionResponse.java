package com.cems.api.dto;

import com.cems.api.entity.SurveyQuestion;

import java.math.BigDecimal;
import java.util.List;

/**
 * A survey question as returned to the builder (spec §3.3). {@code options} is the parsed choice list
 * (empty for rating/open_text). Parsing of the stored JSON text happens in the service layer.
 */
public record QuestionResponse(
        String id,
        int orderIndex,
        String questionText,
        String questionType,
        List<QuestionOption> options,
        BigDecimal weight,
        String needCategoryId,
        String needCategoryName,
        boolean required) {

    public static QuestionResponse fromEntity(SurveyQuestion question, List<QuestionOption> options) {
        return new QuestionResponse(
                question.getId(),
                question.getOrderIndex(),
                question.getQuestionText(),
                question.getQuestionType(),
                options,
                question.getWeight(),
                question.getNeedCategory() == null ? null : question.getNeedCategory().getId(),
                question.getNeedCategory() == null ? null : question.getNeedCategory().getName(),
                question.isRequired());
    }
}
