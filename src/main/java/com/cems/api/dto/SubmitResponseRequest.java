package com.cems.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Public survey submission (spec Module 3 §2/§3). {@code respondentSex} is required (GAD);
 * age group and sector are optional. {@code consent} must be true — RA 10173 (Data Privacy Act)
 * requires explicit consent before collecting the response.
 */
public record SubmitResponseRequest(
        @NotBlank String respondentSex,
        String respondentAgeGroup,
        String respondentSectorId,
        String respondentToken,
        @AssertTrue(message = "Consent is required before submitting.") Boolean consent,
        List<AnswerInput> answers) {

    /**
     * One answer. {@code value} carries rating/choice/open-text answers; {@code values} carries the
     * selected option values for checkbox questions.
     */
    public record AnswerInput(String questionId, String value, List<String> values) {
    }
}
