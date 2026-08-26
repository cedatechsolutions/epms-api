package com.cems.api.service;

import com.cems.api.dto.DashboardOverviewResponse;
import com.cems.api.dto.DashboardOverviewResponse.AssessmentOverview;
import com.cems.api.dto.DashboardOverviewResponse.CommunityOverview;
import com.cems.api.dto.DashboardOverviewResponse.NeedHighlight;
import com.cems.api.dto.DashboardOverviewResponse.RecommendationOverview;
import com.cems.api.entity.Recommendation;
import com.cems.api.entity.Survey;
import com.cems.api.entity.SurveyResponse;
import com.cems.api.repository.AssessmentResultRepository;
import com.cems.api.repository.CommunityRepository;
import com.cems.api.repository.RecommendationRepository;
import com.cems.api.repository.SurveyRepository;
import com.cems.api.repository.SurveyResponseRepository;
import com.cems.api.security.Permissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregator behind {@code GET /api/dashboard/overview} (plan Phase 6 groundwork).
 *
 * <p>Every figure is a SQL aggregate — one query per widget, no per-row loops — so the payload
 * stays a single round trip as the data set grows. Nothing here computes new business meaning:
 * proposal counts come from {@link ProgramService} (which owns per-role visibility) and need
 * priorities from {@link AssessmentScoringService} (which owns the thresholds), so the dashboard
 * can never disagree with the screen a user drills into.
 */
@Service
public class DashboardService {

    /** Rows in the "top identified needs" table. */
    private static final int TOP_NEEDS_LIMIT = 5;

    /** Entries in the dashboard's activity feed. */
    private static final int RECENT_ACTIVITY_LIMIT = 8;

    private final CommunityRepository communityRepository;
    private final SurveyRepository surveyRepository;
    private final SurveyResponseRepository surveyResponseRepository;
    private final AssessmentResultRepository assessmentResultRepository;
    private final RecommendationRepository recommendationRepository;
    private final ProgramService programService;
    private final AssessmentScoringService assessmentScoringService;
    private final ActivityLogService activityLogService;
    private final Permissions permissions;

    public DashboardService(CommunityRepository communityRepository,
            SurveyRepository surveyRepository,
            SurveyResponseRepository surveyResponseRepository,
            AssessmentResultRepository assessmentResultRepository,
            RecommendationRepository recommendationRepository,
            ProgramService programService,
            AssessmentScoringService assessmentScoringService,
            ActivityLogService activityLogService,
            Permissions permissions) {
        this.communityRepository = communityRepository;
        this.surveyRepository = surveyRepository;
        this.surveyResponseRepository = surveyResponseRepository;
        this.assessmentResultRepository = assessmentResultRepository;
        this.recommendationRepository = recommendationRepository;
        this.programService = programService;
        this.assessmentScoringService = assessmentScoringService;
        this.activityLogService = activityLogService;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public DashboardOverviewResponse getOverview() {
        return new DashboardOverviewResponse(
                communityOverview(),
                assessmentOverview(),
                recommendationOverview(),
                // The personal landing overview is not period-scoped: it answers "what is on my
                // plate", which does not end when a semester does.
                programService.getStats(null),
                topNeeds(),
                permissions.canViewActivityLogs() ? activityLogService.recent(RECENT_ACTIVITY_LIMIT) : null,
                Instant.now());
    }

    private CommunityOverview communityOverview() {
        Object[] totals = singleRow(communityRepository.sumProfileTotals());
        return new CommunityOverview(
                communityRepository.countByDeletedAtIsNull(),
                asLong(totals, 3),
                asLong(totals, 0),
                asLong(totals, 1),
                asLong(totals, 2),
                communityRepository.countByDeletedAtIsNullAndPopulationFemaleIsNotNullAndPopulationMaleIsNotNull());
    }

    private AssessmentOverview assessmentOverview() {
        Map<String, Long> byStatus = toCountMap(surveyRepository.countByStatus());
        Map<String, Long> bySex = toCountMap(surveyResponseRepository.countByRespondentSex());
        long responses = bySex.values().stream().mapToLong(Long::longValue).sum();

        return new AssessmentOverview(
                byStatus.values().stream().mapToLong(Long::longValue).sum(),
                byStatus.getOrDefault(Survey.STATUS_DRAFT, 0L),
                byStatus.getOrDefault(Survey.STATUS_DEPLOYED, 0L),
                byStatus.getOrDefault(Survey.STATUS_CLOSED, 0L),
                assessmentResultRepository.countFinalizedSurveys(),
                responses,
                bySex.getOrDefault(SurveyResponse.SEX_FEMALE, 0L),
                bySex.getOrDefault(SurveyResponse.SEX_MALE, 0L),
                bySex.getOrDefault(SurveyResponse.SEX_PREFER_NOT_TO_SAY, 0L));
    }

    private RecommendationOverview recommendationOverview() {
        Map<String, Long> byStatus = toCountMap(recommendationRepository.countByStatus());
        return new RecommendationOverview(
                byStatus.values().stream().mapToLong(Long::longValue).sum(),
                byStatus.getOrDefault(Recommendation.STATUS_PENDING, 0L),
                byStatus.getOrDefault(Recommendation.STATUS_ACCEPTED, 0L),
                byStatus.getOrDefault(Recommendation.STATUS_MODIFIED, 0L),
                byStatus.getOrDefault(Recommendation.STATUS_REJECTED, 0L));
    }

    /** Already ordered strongest-first by the query; this only trims and maps. */
    private List<NeedHighlight> topNeeds() {
        return assessmentResultRepository.aggregateByNeedCategory().stream()
                .limit(TOP_NEEDS_LIMIT)
                .map(row -> {
                    BigDecimal avgScore = asDecimal(row[2]);
                    return new NeedHighlight(
                            (String) row[0],
                            (String) row[1],
                            avgScore,
                            asLong(row, 3),
                            asLong(row, 4),
                            asLong(row, 5),
                            asLong(row, 6),
                            assessmentScoringService.priorityFor(avgScore));
                })
                .toList();
    }

    // --- projection helpers ------------------------------------------------------
    // JPQL aggregates come back as Object[] of assorted Number subtypes (Long, BigDecimal,
    // BigInteger depending on the column and dialect), so read them through Number rather than
    // casting to a concrete type.

    private static Object[] singleRow(List<Object[]> rows) {
        return rows.isEmpty() ? new Object[0] : rows.get(0);
    }

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((String) row[0], asLong(row, 1));
        }
        return counts;
    }

    private static long asLong(Object[] row, int index) {
        if (row.length <= index || !(row[index] instanceof Number number)) {
            return 0L;
        }
        return number.longValue();
    }

    /** Scores are displayed to two decimals throughout the assessment screens; match that here. */
    private static BigDecimal asDecimal(Object value) {
        BigDecimal decimal = value instanceof BigDecimal big
                ? big
                : BigDecimal.valueOf(value instanceof Number number ? number.doubleValue() : 0d);
        return decimal.setScale(2, RoundingMode.HALF_UP);
    }
}
