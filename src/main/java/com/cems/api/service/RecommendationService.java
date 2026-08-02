package com.cems.api.service;

import com.cems.api.dto.RecommendationResponse;
import com.cems.api.dto.RecommendationResponse.CategoryContribution;
import com.cems.api.dto.RecommendationResponse.ScoreBreakdown;
import com.cems.api.entity.AssessmentResult;
import com.cems.api.entity.ProgramType;
import com.cems.api.entity.Recommendation;
import com.cems.api.entity.Sector;
import com.cems.api.entity.Survey;
import com.cems.api.entity.User;
import com.cems.api.exception.ConflictException;
import com.cems.api.repository.AssessmentResultRepository;
import com.cems.api.repository.ProgramTypeNeedWeightRepository;
import com.cems.api.repository.ProgramTypeRepository;
import com.cems.api.repository.RecommendationRepository;
import com.cems.api.repository.SurveyRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.service.RecommendationScoringService.ProgramTypeScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates and manages program recommendations (spec Module 4).
 *
 * <p>Recommendations can only be produced from a <b>finalized</b> assessment: the scores they cite
 * must not move underneath a decision. Re-running the engine replaces {@code pending} rows but keeps
 * decided ones, so the record of what a coordinator accepted or rejected is never rewritten
 * (Module 4 AC 1).
 *
 * <p>Matrix edits deliberately do <b>not</b> rescore existing rows — a recommendation carries the
 * breakdown it was generated with (AC 4).
 */
@Service
public class RecommendationService {

    private final SurveyRepository surveyRepository;
    private final AssessmentResultRepository resultRepository;
    private final ProgramTypeRepository programTypeRepository;
    private final ProgramTypeNeedWeightRepository weightRepository;
    private final RecommendationRepository recommendationRepository;
    private final UserRepository userRepository;
    private final RecommendationScoringService scoringService;
    private final ActivityLogService activityLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RecommendationService(SurveyRepository surveyRepository,
            AssessmentResultRepository resultRepository,
            ProgramTypeRepository programTypeRepository,
            ProgramTypeNeedWeightRepository weightRepository,
            RecommendationRepository recommendationRepository,
            UserRepository userRepository,
            RecommendationScoringService scoringService,
            ActivityLogService activityLogService) {
        this.surveyRepository = surveyRepository;
        this.resultRepository = resultRepository;
        this.programTypeRepository = programTypeRepository;
        this.weightRepository = weightRepository;
        this.recommendationRepository = recommendationRepository;
        this.userRepository = userRepository;
        this.scoringService = scoringService;
        this.activityLogService = activityLogService;
    }

    /**
     * Scores every active program type against the survey's finalized results.
     *
     * @throws ConflictException when results are not finalized, or no active program type exists
     */
    public List<RecommendationResponse> generate(String surveyId) {
        Survey survey = findActive(surveyId);

        List<AssessmentResult> results = resultRepository.findBySurveyIdOrderByRankPositionAsc(surveyId);
        if (results.isEmpty()) {
            throw new ConflictException(
                    "Finalize the assessment results before generating recommendations.");
        }

        List<ProgramType> programTypes = programTypeRepository.findByActiveTrueOrderByNameAsc();
        if (programTypes.isEmpty()) {
            throw new ConflictException(
                    "There are no active program types to match against. Add one in the program-type library.");
        }

        Set<String> communitySectorIds = survey.getCommunity().getSectors().stream()
                .map(Sector::getId)
                .collect(Collectors.toSet());

        List<ProgramTypeScore> scores = scoringService.score(
                results, programTypes, weightRepository.findAll(), communitySectorIds);

        // Replace the previous run's undecided rows; decided ones are audit history and stay put.
        List<Recommendation> stale =
                recommendationRepository.findBySurveyIdAndStatus(surveyId, Recommendation.STATUS_PENDING);
        recommendationRepository.deleteAll(stale);

        List<Recommendation> saved = new ArrayList<>();
        for (ProgramTypeScore score : scores) {
            Recommendation recommendation = new Recommendation();
            recommendation.setSurvey(survey);
            recommendation.setProgramType(programTypes.stream()
                    .filter(type -> type.getId().equals(score.programTypeId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Scored program type no longer exists.")));
            recommendation.setMatchScore(score.matchScore());
            recommendation.setRankPosition(score.rank());
            recommendation.setScoreBreakdown(toJson(score));
            recommendation.setStatus(Recommendation.STATUS_PENDING);
            saved.add(recommendation);
        }
        recommendationRepository.saveAll(saved);

        activityLogService.record("recommendation.generated", "survey", surveyId,
                Map.of("programTypes", saved.size(), "replacedPending", stale.size()));

        return list(surveyId);
    }

    @Transactional(readOnly = true)
    public List<RecommendationResponse> list(String surveyId) {
        findActive(surveyId);
        return recommendationRepository.findBySurveyIdOrderByRankPositionAsc(surveyId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Records a coordinator's ruling. Accepting or modifying is the provenance link a Phase 4
     * program will be born from; rejecting requires a reason.
     *
     * @throws IllegalArgumentException (422) when rejecting without a reason
     * @throws ConflictException        (409) when the recommendation was already decided
     */
    public RecommendationResponse decide(String recommendationId, String status, String note) {
        Recommendation recommendation = recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> new NoSuchElementException("Recommendation not found."));

        if (recommendation.isDecided()) {
            throw new ConflictException("This recommendation has already been "
                    + recommendation.getStatus() + ".");
        }
        if (Recommendation.STATUS_REJECTED.equals(status) && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("A reason is required when rejecting a recommendation.");
        }

        recommendation.setStatus(status);
        recommendation.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
        recommendation.setDecidedBy(currentUserId());
        recommendation.setDecidedAt(Instant.now());
        recommendationRepository.save(recommendation);

        activityLogService.record("recommendation." + status, "recommendation", recommendationId,
                Map.of("programType", recommendation.getProgramType().getName(),
                        "surveyId", recommendation.getSurvey().getId()));

        return toResponse(recommendation);
    }

    // --- helpers ---

    private RecommendationResponse toResponse(Recommendation recommendation) {
        ProgramType programType = recommendation.getProgramType();
        String decidedByName = recommendation.getDecidedBy() == null
                ? null
                : userRepository.findById(recommendation.getDecidedBy())
                        .map(this::displayName)
                        .orElse(null);

        return new RecommendationResponse(
                recommendation.getId(),
                recommendation.getSurvey().getId(),
                programType.getId(),
                programType.getName(),
                programType.getDescription(),
                programType.getDefaultDuration(),
                recommendation.getMatchScore(),
                recommendation.getRankPosition(),
                recommendation.getStatus(),
                recommendation.getDecidedBy(),
                decidedByName,
                recommendation.getDecidedAt(),
                recommendation.getDecisionNote(),
                recommendation.getCreatedAt(),
                parseBreakdown(recommendation.getScoreBreakdown()));
    }

    private String displayName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isBlank() ? user.getEmail() : name;
    }

    private String toJson(ProgramTypeScore score) {
        ScoreBreakdown breakdown = new ScoreBreakdown(
                score.contributions().stream()
                        .map(contribution -> new CategoryContribution(
                                contribution.needCategoryId(),
                                contribution.needCategoryName(),
                                contribution.avgScore(),
                                contribution.priority(),
                                contribution.multiplier(),
                                contribution.weight(),
                                contribution.contribution()))
                        .toList(),
                score.rawScore(),
                score.maxTheoretical(),
                score.sectorBonus(),
                score.sectorBonusApplied(),
                score.matchedSectors(),
                score.matchScore());
        try {
            return objectMapper.writeValueAsString(breakdown);
        } catch (Exception ex) {
            // The score itself is still valid; losing the explanation must not fail the run.
            return null;
        }
    }

    private ScoreBreakdown parseBreakdown(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ScoreBreakdown.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).map(User::getId).orElse(null);
    }

    private Survey findActive(String id) {
        return surveyRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NoSuchElementException("Survey not found."));
    }
}
