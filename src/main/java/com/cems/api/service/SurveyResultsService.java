package com.cems.api.service;

import com.cems.api.dto.QuestionOption;
import com.cems.api.dto.SurveyResultsResponse;
import com.cems.api.dto.SurveyResultsResponse.CategoryResult;
import com.cems.api.dto.SurveyResultsResponse.QuestionDistribution;
import com.cems.api.entity.AssessmentResult;
import com.cems.api.entity.Survey;
import com.cems.api.entity.SurveyAnswer;
import com.cems.api.entity.SurveyQuestion;
import com.cems.api.entity.SurveyResponse;
import com.cems.api.exception.ConflictException;
import com.cems.api.repository.AssessmentResultRepository;
import com.cems.api.repository.NeedCategoryRepository;
import com.cems.api.repository.SurveyAnswerRepository;
import com.cems.api.repository.SurveyQuestionRepository;
import com.cems.api.repository.SurveyRepository;
import com.cems.api.repository.SurveyResponseRepository;
import com.cems.api.service.AssessmentScoringService.CategoryScore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Assessment results (spec Module 3 §4): live computation, the "finalize" snapshot, and the data
 * the exports and the QF-23 report are built from.
 *
 * <p>Live results are recomputed on every read while a survey is open. Once finalized, the
 * {@code assessment_results} snapshot becomes the source of truth so recommendations (Phase 3) and
 * the QF-23 report always cite exactly the numbers that were approved.
 */
@Service
public class SurveyResultsService {

    /** Cap on open-text answers returned to the UI, so a popular survey cannot flood the payload. */
    private static final int MAX_TEXT_ANSWERS = 200;

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository questionRepository;
    private final SurveyResponseRepository responseRepository;
    private final SurveyAnswerRepository answerRepository;
    private final AssessmentResultRepository resultRepository;
    private final NeedCategoryRepository needCategoryRepository;
    private final AssessmentScoringService scoringService;
    private final ActivityLogService activityLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SurveyResultsService(SurveyRepository surveyRepository,
            SurveyQuestionRepository questionRepository,
            SurveyResponseRepository responseRepository,
            SurveyAnswerRepository answerRepository,
            AssessmentResultRepository resultRepository,
            NeedCategoryRepository needCategoryRepository,
            AssessmentScoringService scoringService,
            ActivityLogService activityLogService) {
        this.surveyRepository = surveyRepository;
        this.questionRepository = questionRepository;
        this.responseRepository = responseRepository;
        this.answerRepository = answerRepository;
        this.resultRepository = resultRepository;
        this.needCategoryRepository = needCategoryRepository;
        this.scoringService = scoringService;
        this.activityLogService = activityLogService;
    }

    @Transactional(readOnly = true)
    public SurveyResultsResponse getResults(String surveyId) {
        Survey survey = findActive(surveyId);
        List<SurveyAnswer> answers = answerRepository.findAllBySurveyId(surveyId);
        List<AssessmentResult> snapshot = resultRepository.findBySurveyIdOrderByRankPositionAsc(surveyId);
        boolean finalized = !snapshot.isEmpty();

        List<CategoryResult> categories = finalized
                ? snapshot.stream().map(this::toCategoryResult).toList()
                : scoringService.score(answers).stream().map(this::toCategoryResult).toList();

        long total = responseRepository.countBySurveyId(surveyId);
        long female = responseRepository.countBySurveyIdAndRespondentSex(surveyId, SurveyResponse.SEX_FEMALE);
        long male = responseRepository.countBySurveyIdAndRespondentSex(surveyId, SurveyResponse.SEX_MALE);

        BigDecimal completionRate = null;
        Integer target = survey.getTargetResponses();
        if (target != null && target > 0) {
            completionRate = BigDecimal.valueOf(total)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(target), 1, RoundingMode.HALF_UP);
        }

        BigDecimal highest = categories.stream()
                .map(CategoryResult::avgScore)
                .max(BigDecimal::compareTo)
                .orElse(null);

        Instant finalizedAt = finalized ? snapshot.get(0).getComputedAt() : null;

        return new SurveyResultsResponse(
                survey.getId(),
                survey.getTitle(),
                survey.getCommunity().getName(),
                survey.getStatus(),
                finalized,
                finalizedAt,
                total,
                female,
                male,
                total - female - male,
                target,
                completionRate,
                highest,
                categories,
                buildDistributions(surveyId, answers));
    }

    /**
     * Freezes the current scores into {@code assessment_results} (spec Module 3 §4). Requires at
     * least one response, and cannot be repeated — the snapshot is what downstream modules cite.
     */
    public SurveyResultsResponse finalizeResults(String surveyId) {
        Survey survey = findActive(surveyId);

        if (survey.isDraft()) {
            throw new ConflictException("Deploy and collect responses before finalizing results.");
        }
        if (resultRepository.existsBySurveyId(surveyId)) {
            throw new ConflictException("Results for this survey are already finalized.");
        }
        if (responseRepository.countBySurveyId(surveyId) == 0) {
            throw new ConflictException("There are no responses to finalize.");
        }

        List<CategoryScore> scores = scoringService.score(answerRepository.findAllBySurveyId(surveyId));
        if (scores.isEmpty()) {
            throw new ConflictException(
                    "No rating answers were collected, so there is nothing to score. "
                            + "Only rating questions contribute to need scores.");
        }

        Instant computedAt = Instant.now();
        List<AssessmentResult> snapshot = new ArrayList<>();
        for (CategoryScore score : scores) {
            AssessmentResult result = new AssessmentResult();
            result.setSurvey(survey);
            result.setNeedCategory(needCategoryRepository.findById(score.needCategoryId())
                    .orElseThrow(() -> new IllegalStateException("Scored category no longer exists.")));
            result.setAvgScore(score.avgScore());
            result.setResponseCount(score.responseCount());
            result.setFemaleCount(score.femaleCount());
            result.setMaleCount(score.maleCount());
            result.setPriority(score.priority());
            result.setRankPosition(score.rank());
            result.setComputedAt(computedAt);
            snapshot.add(result);
        }
        resultRepository.saveAll(snapshot);

        activityLogService.record("survey.results_finalized", "survey", surveyId,
                Map.of("categories", snapshot.size()));

        return getResults(surveyId);
    }

    // --- helpers ---

    /** Distributions for the question types that are collected but not scored in v1. */
    private List<QuestionDistribution> buildDistributions(String surveyId, List<SurveyAnswer> answers) {
        List<SurveyQuestion> questions = questionRepository.findBySurveyIdOrderByOrderIndexAsc(surveyId);
        List<QuestionDistribution> distributions = new ArrayList<>();

        for (SurveyQuestion question : questions) {
            String type = question.getQuestionType();
            if ("rating".equals(type)) {
                continue; // rating questions are represented by the category scores
            }

            List<String> answerValues = answers.stream()
                    .filter(answer -> answer.getSurveyQuestion() != null
                            && question.getId().equals(answer.getSurveyQuestion().getId()))
                    .map(SurveyAnswer::getAnswerValue)
                    .filter(value -> value != null && !value.isBlank())
                    .toList();

            if ("open_text".equals(type)) {
                distributions.add(new QuestionDistribution(
                        question.getId(), question.getQuestionText(), type, Map.of(),
                        answerValues.stream().limit(MAX_TEXT_ANSWERS).toList()));
                continue;
            }

            // Choice types: count selections, labelled with the author's option labels.
            Map<String, String> labelByValue = new LinkedHashMap<>();
            for (QuestionOption option : parseOptions(question.getOptions())) {
                labelByValue.put(option.value(), option.label());
            }

            Map<String, Long> counts = new LinkedHashMap<>();
            labelByValue.values().forEach(label -> counts.put(label, 0L));

            for (String raw : answerValues) {
                for (String selected : decodeSelections(raw)) {
                    String label = labelByValue.getOrDefault(selected, selected);
                    counts.merge(label, 1L, Long::sum);
                }
            }
            distributions.add(new QuestionDistribution(
                    question.getId(), question.getQuestionText(), type, counts, List.of()));
        }
        return distributions;
    }

    /** Checkbox answers are stored as a JSON array; single-choice answers as a bare value. */
    private List<String> decodeSelections(String rawValue) {
        String trimmed = rawValue.trim();
        if (trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, new TypeReference<List<String>>() {
                });
            } catch (Exception ex) {
                return List.of();
            }
        }
        return List.of(trimmed);
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

    private CategoryResult toCategoryResult(CategoryScore score) {
        return new CategoryResult(
                score.needCategoryId(),
                score.needCategoryName(),
                score.avgScore(),
                score.responseCount(),
                score.femaleCount(),
                score.maleCount(),
                score.priority(),
                score.rank());
    }

    private CategoryResult toCategoryResult(AssessmentResult result) {
        return new CategoryResult(
                result.getNeedCategory().getId(),
                result.getNeedCategory().getName(),
                result.getAvgScore(),
                result.getResponseCount(),
                result.getFemaleCount(),
                result.getMaleCount(),
                result.getPriority(),
                result.getRankPosition());
    }

    private Survey findActive(String id) {
        return surveyRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NoSuchElementException("Survey not found."));
    }
}
