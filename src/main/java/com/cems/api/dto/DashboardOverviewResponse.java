package com.cems.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Single-payload snapshot of everything the system currently holds, for the landing dashboard.
 *
 * <p>This is the pre-Phase-6 overview: it aggregates the modules that exist today (communities,
 * needs assessments, recommendations, proposals) and deliberately carries <em>no</em> academic-period
 * filter, beneficiary attendance, or completion-rate widgets — those depend on Modules 5b/6 and must
 * not be faked with placeholder numbers. When the real M&amp;E dashboard lands, this payload is
 * extended rather than replaced.
 *
 * <p>Every person count is sex-disaggregated (cross-cutting rule 1). {@code recentActivity} is
 * {@code null} for callers who may not read the audit trail, so the client hides the card outright
 * rather than rendering an empty one.
 */
public record DashboardOverviewResponse(
        CommunityOverview communities,
        AssessmentOverview assessments,
        RecommendationOverview recommendations,
        ProgramStatsResponse programs,
        List<NeedHighlight> topNeeds,
        List<ActivityLogResponse> recentActivity,
        Instant generatedAt) {

    /**
     * Partner-community reach. {@code population} sums the profiled estimate; the female/male sums
     * cover only the communities that recorded a split, which is why {@code withSexSplit} is
     * reported alongside — a client showing the split without it would overstate coverage.
     */
    public record CommunityOverview(
            long total,
            long households,
            long population,
            long populationFemale,
            long populationMale,
            long withSexSplit) {
    }

    /** Needs-assessment activity. {@code finalized} counts surveys with a snapshot in assessment_results. */
    public record AssessmentOverview(
            long surveys,
            long draft,
            long deployed,
            long closed,
            long finalized,
            long responses,
            long responsesFemale,
            long responsesMale,
            long responsesUndisclosed) {
    }

    /** Recommendation-engine decisions outstanding and made. */
    public record RecommendationOverview(
            long total,
            long pending,
            long accepted,
            long modified,
            long rejected) {
    }

    /**
     * One need category rolled up across every finalized assessment. {@code avgScore} is the mean of
     * the per-survey snapshot averages (each survey counts once, regardless of how many respondents
     * it drew) and {@code priority} is derived from it with the same thresholds the survey results
     * screen uses, so a category cannot show one priority here and another there.
     */
    public record NeedHighlight(
            String needCategoryId,
            String needCategoryName,
            BigDecimal avgScore,
            long assessmentCount,
            long responseCount,
            long femaleCount,
            long maleCount,
            String priority) {
    }
}
