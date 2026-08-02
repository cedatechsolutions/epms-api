package com.cems.api.service;

import com.cems.api.dto.PublicQuestionResponse;
import com.cems.api.dto.PublicSurveyResponse;
import com.cems.api.dto.QuestionOption;
import com.cems.api.dto.SectorResponse;
import com.cems.api.dto.SubmitResponseRequest;
import com.cems.api.entity.Sector;
import com.cems.api.entity.Survey;
import com.cems.api.entity.SurveyAnswer;
import com.cems.api.entity.SurveyQuestion;
import com.cems.api.entity.SurveyResponse;
import com.cems.api.exception.ConflictException;
import com.cems.api.exception.GoneException;
import com.cems.api.repository.SectorRepository;
import com.cems.api.repository.SurveyQuestionRepository;
import com.cems.api.repository.SurveyRepository;
import com.cems.api.repository.SurveyResponseRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Public (unauthenticated) survey access and submission (spec Module 3 §3).
 *
 * <p>The server is authoritative: the closing date, required questions, allowed answer values and
 * the mandatory GAD sex field are all enforced here regardless of what the client sends. Closed or
 * expired surveys return 410; unknown tokens return 404 without revealing anything.
 */
@Service
public class PublicSurveyService {

    private static final Set<String> ALLOWED_SEX =
            Set.of(SurveyResponse.SEX_FEMALE, SurveyResponse.SEX_MALE, SurveyResponse.SEX_PREFER_NOT_TO_SAY);
    private static final Set<String> ALLOWED_AGE_GROUPS =
            Set.of("under_18", "18_30", "31_45", "46_59", "60_plus");

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository questionRepository;
    private final SurveyResponseRepository responseRepository;
    private final SectorRepository sectorRepository;
    private final ActivityLogService activityLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PublicSurveyService(SurveyRepository surveyRepository,
            SurveyQuestionRepository questionRepository,
            SurveyResponseRepository responseRepository,
            SectorRepository sectorRepository,
            ActivityLogService activityLogService) {
        this.surveyRepository = surveyRepository;
        this.questionRepository = questionRepository;
        this.responseRepository = responseRepository;
        this.sectorRepository = sectorRepository;
        this.activityLogService = activityLogService;
    }

    @Transactional(readOnly = true)
    public PublicSurveyResponse getByToken(String token) {
        Survey survey = findOpenSurvey(token);
        List<PublicQuestionResponse> questions =
                questionRepository.findBySurveyIdOrderByOrderIndexAsc(survey.getId()).stream()
                        .map(question -> PublicQuestionResponse.fromEntity(question, parseOptions(question.getOptions())))
                        .toList();
        List<SectorResponse> sectors = sectorRepository.findByActiveTrueOrderByName().stream()
                .map(SectorResponse::fromEntity)
                .toList();

        return new PublicSurveyResponse(
                survey.getTitle(),
                survey.getDescription(),
                survey.getCommunity().getName(),
                survey.getClosesAt(),
                questions,
                sectors);
    }

    public void submit(String token, SubmitResponseRequest request, String ipAddress) {
        Survey survey = findOpenSurvey(token);

        // Best-effort per-device dedupe (spec: not bulletproof, and that is accepted).
        String respondentToken = trimToNull(request.respondentToken());
        if (respondentToken != null
                && responseRepository.existsBySurveyIdAndRespondentToken(survey.getId(), respondentToken)) {
            throw new ConflictException("A response has already been submitted from this device.");
        }

        SurveyResponse response = new SurveyResponse();
        response.setSurvey(survey);
        response.setRespondentToken(respondentToken);
        response.setRespondentSex(validateSex(request.respondentSex()));
        response.setRespondentAgeGroup(validateAgeGroup(request.respondentAgeGroup()));
        response.setRespondentSector(resolveSector(request.respondentSectorId()));
        response.setSubmittedAt(Instant.now());
        response.setIpAddress(ipAddress);
        response.setAnswers(buildAnswers(survey, response, request.answers()));

        responseRepository.save(response);
        activityLogService.record("survey.response_submitted", "survey", survey.getId(),
                Map.of("sex", response.getRespondentSex()));
    }

    // --- validation helpers ---

    /**
     * Resolves a survey by its public token and asserts it is currently accepting responses.
     * Unknown token → 404. Closed, not yet open, or past the closing date → 410.
     */
    private Survey findOpenSurvey(String token) {
        Survey survey = surveyRepository.findByAccessTokenAndDeletedAtIsNull(token)
                .orElseThrow(() -> new NoSuchElementException("Survey not found."));

        if (survey.isClosed()) {
            throw new GoneException("This survey is closed and is no longer accepting responses.");
        }
        if (!survey.isDeployed()) {
            // A draft has no token, but never leak lifecycle details on the public surface.
            throw new NoSuchElementException("Survey not found.");
        }

        Instant now = Instant.now();
        if (survey.getClosesAt() != null && now.isAfter(survey.getClosesAt())) {
            throw new GoneException("This survey closed on the scheduled date and is no longer accepting responses.");
        }
        if (survey.getOpensAt() != null && now.isBefore(survey.getOpensAt())) {
            throw new GoneException("This survey is not open yet. Please check back later.");
        }
        return survey;
    }

    private String validateSex(String sex) {
        String normalized = sex == null ? "" : sex.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SEX.contains(normalized)) {
            throw new IllegalArgumentException("Sex is required and must be female, male, or prefer_not_to_say.");
        }
        return normalized;
    }

    private String validateAgeGroup(String ageGroup) {
        String normalized = trimToNull(ageGroup);
        if (normalized == null) {
            return null;
        }
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (!ALLOWED_AGE_GROUPS.contains(lowered)) {
            throw new IllegalArgumentException("Age group is invalid.");
        }
        return lowered;
    }

    private Sector resolveSector(String sectorId) {
        String normalized = trimToNull(sectorId);
        if (normalized == null) {
            return null;
        }
        return sectorRepository.findById(normalized)
                .orElseThrow(() -> new IllegalArgumentException("Selected sector does not exist."));
    }

    /** Validates every answer against its question and enforces required questions. */
    private List<SurveyAnswer> buildAnswers(Survey survey,
            SurveyResponse response,
            List<SubmitResponseRequest.AnswerInput> inputs) {
        List<SurveyQuestion> questions = questionRepository.findBySurveyIdOrderByOrderIndexAsc(survey.getId());
        Map<String, SubmitResponseRequest.AnswerInput> byQuestionId = new HashMap<>();
        for (SubmitResponseRequest.AnswerInput input : inputs == null ? List.<SubmitResponseRequest.AnswerInput>of() : inputs) {
            if (input.questionId() != null) {
                byQuestionId.put(input.questionId(), input);
            }
        }

        List<SurveyAnswer> answers = new ArrayList<>();
        for (SurveyQuestion question : questions) {
            SubmitResponseRequest.AnswerInput input = byQuestionId.remove(question.getId());
            String value = encodeAnswer(question, input);

            if (value == null) {
                if (question.isRequired()) {
                    throw new IllegalArgumentException("Please answer: " + question.getQuestionText());
                }
                continue;
            }

            SurveyAnswer answer = new SurveyAnswer();
            answer.setSurveyResponse(response);
            answer.setSurveyQuestion(question);
            answer.setAnswerValue(value);
            answers.add(answer);
        }

        if (!byQuestionId.isEmpty()) {
            throw new IllegalArgumentException("The submission contains answers to unknown questions.");
        }
        return answers;
    }

    /** Encodes one answer to its stored text form, or null when the question was left blank. */
    private String encodeAnswer(SurveyQuestion question, SubmitResponseRequest.AnswerInput input) {
        if (input == null) {
            return null;
        }
        Set<String> allowed = optionValues(question);

        return switch (question.getQuestionType()) {
            case "rating" -> encodeRating(input.value());
            case "multiple_choice" -> {
                String value = trimToNull(input.value());
                if (value == null) {
                    yield null;
                }
                if (!allowed.contains(value)) {
                    throw new IllegalArgumentException("Invalid option for: " + question.getQuestionText());
                }
                yield value;
            }
            case "checkbox" -> {
                List<String> values = input.values() == null ? List.of() : input.values().stream()
                        .map(this::trimToNull)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                if (values.isEmpty()) {
                    yield null;
                }
                if (!allowed.containsAll(values)) {
                    throw new IllegalArgumentException("Invalid option for: " + question.getQuestionText());
                }
                try {
                    yield objectMapper.writeValueAsString(values);
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Could not record the answer for: " + question.getQuestionText());
                }
            }
            case "open_text" -> trimToNull(input.value());
            default -> null;
        };
    }

    /** Rating answers must be a whole number 1–5 (spec: rating 1–5). */
    private String encodeRating(String rawValue) {
        String value = trimToNull(rawValue);
        if (value == null) {
            return null;
        }
        int rating;
        try {
            rating = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Rating answers must be a number from 1 to 5.");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating answers must be a number from 1 to 5.");
        }
        return String.valueOf(rating);
    }

    private Set<String> optionValues(SurveyQuestion question) {
        return parseOptions(question.getOptions()).stream()
                .map(QuestionOption::value)
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<QuestionOption> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<QuestionOption>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
