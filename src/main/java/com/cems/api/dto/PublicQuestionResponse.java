package com.cems.api.dto;

import com.cems.api.entity.SurveyQuestion;

import java.util.List;

/**
 * A question as shown on the public form. Deliberately omits scoring metadata (weight, need
 * category) — respondents have no need for it and the public surface stays minimal (spec §5:
 * "Public survey endpoints expose no PII").
 */
public record PublicQuestionResponse(
        String id,
        int orderIndex,
        String questionText,
        String questionType,
        List<QuestionOption> options,
        boolean required) {

    public static PublicQuestionResponse fromEntity(SurveyQuestion question, List<QuestionOption> options) {
        return new PublicQuestionResponse(
                question.getId(),
                question.getOrderIndex(),
                question.getQuestionText(),
                question.getQuestionType(),
                options,
                question.isRequired());
    }
}
