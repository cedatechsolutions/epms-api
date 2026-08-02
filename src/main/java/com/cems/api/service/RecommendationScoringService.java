package com.cems.api.service;

import com.cems.api.entity.AssessmentResult;
import com.cems.api.entity.ProgramType;
import com.cems.api.entity.ProgramTypeNeedWeight;
import com.cems.api.entity.Sector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Scores program types against a finalized assessment (spec Module 4).
 *
 * <p><b>This is a weighted scoring matrix, not machine learning</b> — explainability over
 * sophistication. Every number the UI shows must be reproducible from the breakdown this service
 * returns, which is the module's stated acceptance criterion.
 *
 * <p><b>The formula, exactly as specified:</b>
 * <pre>
 * participating = need categories that BOTH appear in the finalized results
 *                 AND carry a non-zero matrix weight for this program type
 * raw   = Σ over participating c:  avg_score(c) × weight(pt,c) × multiplier(priority(c))
 * max   = Σ over participating c:  5.00 × weight(pt,c) × 1.5
 * bonus = 10% of raw, when the program type's sectors intersect the community's sectors
 * score = min(100, (raw + bonus) / max × 100)
 * </pre>
 *
 * <p>Two deliberate choices worth knowing:
 * <ul>
 *   <li><b>{@code max} spans only participating categories.</b> Normalizing over every category the
 *       type maps to would penalize it for questions the survey never asked — that is a property of
 *       the survey, not of how well the program fits.</li>
 *   <li><b>The 100 cap is reached in practice, not just defensively.</b> A perfect fit (every
 *       participating need critical at 5.00) gives {@code raw == max}; the sector bonus then pushes
 *       the ratio to 110, and the cap keeps the score inside the spec's 0–100 range.</li>
 * </ul>
 *
 * <p><b>What matrix weights do and do not change.</b> Because {@code max} is built from the same
 * weights as {@code raw}, scaling <em>every</em> participating weight of a type by the same factor
 * leaves its score untouched — a type mapped only to Health scores the same at weight 5.0 as at
 * weight 1.0. Weights change a score by altering the <em>balance</em> between the categories a type
 * addresses, or by dropping to 0, which removes the category from the calculation entirely. This is
 * inherent to normalizing per program type: the score measures fit, not magnitude. Admins editing
 * the matrix should think in terms of relative emphasis between needs, not absolute strength.
 *
 * <p>Each contribution is rounded to 2 decimals <em>before</em> being summed, so the stored
 * breakdown adds up exactly to the raw score a reviewer sees — no invisible rounding drift.
 *
 * <p>This class is pure and deterministic: the same results and matrix always produce the same
 * ranking. Ties break by program-type name so the order is stable. Covered by the spec-mandated
 * fixture test (§5.4).
 */
@Service
public class RecommendationScoringService {

    /** Highest possible answer on the 1–5 rating scale, used for the theoretical maximum. */
    private static final BigDecimal MAX_AVG_SCORE = new BigDecimal("5.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_MATCH_SCORE = new BigDecimal("100.00");

    private final BigDecimal criticalMultiplier;
    private final BigDecimal highMultiplier;
    private final BigDecimal moderateMultiplier;
    private final BigDecimal lowMultiplier;
    private final BigDecimal sectorBonusRate;

    public RecommendationScoringService(
            @Value("${app.recommendation.multiplier.critical:1.5}") BigDecimal criticalMultiplier,
            @Value("${app.recommendation.multiplier.high:1.25}") BigDecimal highMultiplier,
            @Value("${app.recommendation.multiplier.moderate:1.0}") BigDecimal moderateMultiplier,
            @Value("${app.recommendation.multiplier.low:0.75}") BigDecimal lowMultiplier,
            @Value("${app.recommendation.sector-bonus:0.10}") BigDecimal sectorBonusRate) {
        this.criticalMultiplier = criticalMultiplier;
        this.highMultiplier = highMultiplier;
        this.moderateMultiplier = moderateMultiplier;
        this.lowMultiplier = lowMultiplier;
        this.sectorBonusRate = sectorBonusRate;
    }

    /** One need category's share of a program type's raw score — the "why?" popover's rows. */
    public record CategoryContribution(
            String needCategoryId,
            String needCategoryName,
            BigDecimal avgScore,
            String priority,
            BigDecimal multiplier,
            BigDecimal weight,
            BigDecimal contribution) {
    }

    /** One scored program type. {@code rank} is 1-based, highest match first. */
    public record ProgramTypeScore(
            String programTypeId,
            String programTypeName,
            BigDecimal matchScore,
            int rank,
            BigDecimal rawScore,
            BigDecimal maxTheoretical,
            BigDecimal sectorBonus,
            boolean sectorBonusApplied,
            List<String> matchedSectors,
            List<CategoryContribution> contributions) {
    }

    /**
     * Scores every supplied program type against the finalized results.
     *
     * @param results            the {@code assessment_results} snapshot — finalized numbers only
     * @param programTypes       candidates (callers pass the active ones)
     * @param weights            matrix rows for those types; missing rows mean weight 0
     * @param communitySectorIds sectors tagged on the survey's community, for the bonus
     */
    public List<ProgramTypeScore> score(List<AssessmentResult> results,
            List<ProgramType> programTypes,
            List<ProgramTypeNeedWeight> weights,
            Set<String> communitySectorIds) {

        Map<String, Map<String, BigDecimal>> weightsByType = groupWeights(weights);

        List<ProgramTypeScore> scores = new ArrayList<>();
        for (ProgramType programType : programTypes) {
            scores.add(scoreOne(
                    programType,
                    results,
                    weightsByType.getOrDefault(programType.getId(), Map.of()),
                    communitySectorIds));
        }

        // Best match first; ties break by name so repeated runs produce an identical ordering.
        scores.sort(Comparator.comparing(ProgramTypeScore::matchScore).reversed()
                .thenComparing(ProgramTypeScore::programTypeName));

        List<ProgramTypeScore> ranked = new ArrayList<>(scores.size());
        for (int index = 0; index < scores.size(); index++) {
            ProgramTypeScore score = scores.get(index);
            ranked.add(new ProgramTypeScore(
                    score.programTypeId(),
                    score.programTypeName(),
                    score.matchScore(),
                    index + 1,
                    score.rawScore(),
                    score.maxTheoretical(),
                    score.sectorBonus(),
                    score.sectorBonusApplied(),
                    score.matchedSectors(),
                    score.contributions()));
        }
        return ranked;
    }

    /** Priority multiplier per spec Module 4 (critical 1.5, high 1.25, moderate 1.0, low 0.75). */
    public BigDecimal multiplierFor(String priority) {
        return switch (priority == null ? "" : priority) {
            case AssessmentResult.PRIORITY_CRITICAL -> criticalMultiplier;
            case AssessmentResult.PRIORITY_HIGH -> highMultiplier;
            case AssessmentResult.PRIORITY_MODERATE -> moderateMultiplier;
            default -> lowMultiplier;
        };
    }

    // --- internals ---

    private ProgramTypeScore scoreOne(ProgramType programType,
            List<AssessmentResult> results,
            Map<String, BigDecimal> weightByCategory,
            Set<String> communitySectorIds) {

        List<CategoryContribution> contributions = new ArrayList<>();
        BigDecimal raw = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal max = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        for (AssessmentResult result : results) {
            String categoryId = result.getNeedCategory().getId();
            BigDecimal weight = weightByCategory.get(categoryId);
            if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // this program type does not address this need
            }

            BigDecimal multiplier = multiplierFor(result.getPriority());
            BigDecimal contribution = result.getAvgScore()
                    .multiply(weight)
                    .multiply(multiplier)
                    .setScale(2, RoundingMode.HALF_UP);

            contributions.add(new CategoryContribution(
                    categoryId,
                    result.getNeedCategory().getName(),
                    result.getAvgScore(),
                    result.getPriority(),
                    multiplier,
                    weight,
                    contribution));

            raw = raw.add(contribution);
            max = max.add(MAX_AVG_SCORE.multiply(weight).multiply(criticalMultiplier)
                    .setScale(2, RoundingMode.HALF_UP));
        }

        Set<String> matchedSectors = matchedSectorNames(programType, communitySectorIds);
        boolean bonusApplies = !matchedSectors.isEmpty() && raw.compareTo(BigDecimal.ZERO) > 0;
        BigDecimal bonus = bonusApplies
                ? raw.multiply(sectorBonusRate).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        // No participating category means nothing to normalize against — the type scores 0, not NaN.
        BigDecimal matchScore = max.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : raw.add(bonus).multiply(HUNDRED).divide(max, 2, RoundingMode.HALF_UP);
        if (matchScore.compareTo(MAX_MATCH_SCORE) > 0) {
            matchScore = MAX_MATCH_SCORE;
        }

        return new ProgramTypeScore(
                programType.getId(),
                programType.getName(),
                matchScore,
                0, // rank assigned once every type is scored
                raw,
                max,
                bonus,
                bonusApplies,
                List.copyOf(matchedSectors),
                List.copyOf(contributions));
    }

    private Map<String, Map<String, BigDecimal>> groupWeights(List<ProgramTypeNeedWeight> weights) {
        Map<String, Map<String, BigDecimal>> grouped = new HashMap<>();
        for (ProgramTypeNeedWeight weight : weights) {
            if (weight.getProgramType() == null || weight.getNeedCategory() == null) {
                continue;
            }
            grouped.computeIfAbsent(weight.getProgramType().getId(), ignored -> new HashMap<>())
                    .put(weight.getNeedCategory().getId(), weight.getWeight());
        }
        return grouped;
    }

    /** Sorted so the stored breakdown lists the same sectors in the same order on every run. */
    private Set<String> matchedSectorNames(ProgramType programType, Set<String> communitySectorIds) {
        Set<String> matched = new TreeSet<>();
        for (Sector sector : programType.getSectors()) {
            if (communitySectorIds.contains(sector.getId())) {
                matched.add(sector.getName());
            }
        }
        return matched;
    }
}
