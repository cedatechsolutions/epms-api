package com.cems.api.service;

import com.cems.api.entity.AssessmentResult;
import com.cems.api.entity.NeedCategory;
import com.cems.api.entity.ProgramType;
import com.cems.api.entity.ProgramTypeNeedWeight;
import com.cems.api.entity.Sector;
import com.cems.api.service.RecommendationScoringService.CategoryContribution;
import com.cems.api.service.RecommendationScoringService.ProgramTypeScore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §5.4 MANDATORY test: a fixed matrix + fixed finalized results must produce exactly the
 * hand-computed match scores and ranks. Every expected value below is derived by hand from the
 * Module 4 formula:
 *
 * <pre>
 * raw   = Σ avg_score × weight × priority_multiplier   (critical 1.5, high 1.25, moderate 1.0, low 0.75)
 * max   = Σ 5.00      × weight × 1.5                   (participating categories only)
 * bonus = 10% of raw when sectors intersect
 * score = min(100, (raw + bonus) / max × 100)
 * </pre>
 */
class RecommendationScoringServiceTest {

    private final RecommendationScoringService scoring = new RecommendationScoringService(
            new BigDecimal("1.5"), new BigDecimal("1.25"), new BigDecimal("1.0"),
            new BigDecimal("0.75"), new BigDecimal("0.10"));

    private static final NeedCategory HEALTH = category("cat-health", "Health");
    private static final NeedCategory LIVELIHOOD = category("cat-livelihood", "Livelihood");
    private static final NeedCategory EDUCATION = category("cat-education", "Education");

    private static final Sector WOMEN = sector("sec-women", "Women");
    private static final Sector MSMES = sector("sec-msmes", "MSMEs");

    /** Finalized results shared by most cases: Health critical, Livelihood high, Education low. */
    private static List<AssessmentResult> results() {
        return List.of(
                result(HEALTH, "4.50", "critical", 1),
                result(LIVELIHOOD, "3.50", "high", 2),
                result(EDUCATION, "2.00", "low", 3));
    }

    @Test
    void producesHandComputedMatchScoresAndRanks() {
        ProgramType healthCaravan = programType("pt-health", "Health Caravan", WOMEN);
        ProgramType livelihoodTraining = programType("pt-livelihood", "Livelihood Training", MSMES);
        ProgramType communityDev = programType("pt-community", "Community Development");
        ProgramType sportsFest = programType("pt-sports", "Sports Festival");

        List<ProgramTypeNeedWeight> weights = new ArrayList<>();
        weights.add(weight(healthCaravan, HEALTH, "5.0"));
        weights.add(weight(livelihoodTraining, LIVELIHOOD, "5.0"));
        weights.add(weight(livelihoodTraining, EDUCATION, "2.0"));
        weights.add(weight(communityDev, HEALTH, "2.0"));
        weights.add(weight(communityDev, LIVELIHOOD, "2.0"));
        weights.add(weight(communityDev, EDUCATION, "2.0"));
        // Sports Festival is deliberately unmapped — it must score 0, not blow up.

        // The community serves Women, so only Health Caravan earns the sector bonus.
        List<ProgramTypeScore> scores = scoring.score(
                results(),
                List.of(healthCaravan, livelihoodTraining, communityDev, sportsFest),
                weights,
                Set.of(WOMEN.getId()));

        assertEquals(4, scores.size(), "every candidate type is scored, even unmapped ones");

        // --- Health Caravan (rank 1) -----------------------------------------
        // Health only: 4.50 × 5.0 × 1.5 = 33.75 -> raw 33.75
        // max   = 5.00 × 5.0 × 1.5 = 37.50
        // bonus = 33.75 × 0.10 = 3.375 -> 3.38
        // score = (33.75 + 3.38) × 100 / 37.50 = 99.0133... -> 99.01
        ProgramTypeScore health = scores.get(0);
        assertEquals("Health Caravan", health.programTypeName());
        assertEquals(1, health.rank());
        assertEquals(new BigDecimal("33.75"), health.rawScore());
        assertEquals(new BigDecimal("37.50"), health.maxTheoretical());
        assertEquals(new BigDecimal("3.38"), health.sectorBonus());
        assertTrue(health.sectorBonusApplied());
        assertEquals(List.of("Women"), health.matchedSectors());
        assertEquals(new BigDecimal("99.01"), health.matchScore());

        // --- Community Development (rank 2) ----------------------------------
        // Health      4.50 × 2.0 × 1.50 = 13.50
        // Livelihood  3.50 × 2.0 × 1.25 =  8.75
        // Education   2.00 × 2.0 × 0.75 =  3.00   -> raw 25.25
        // max = 3 × (5.00 × 2.0 × 1.5) = 45.00 ; no sector overlap -> no bonus
        // score = 25.25 × 100 / 45.00 = 56.111... -> 56.11
        ProgramTypeScore community = scores.get(1);
        assertEquals("Community Development", community.programTypeName());
        assertEquals(2, community.rank());
        assertEquals(new BigDecimal("25.25"), community.rawScore());
        assertEquals(new BigDecimal("45.00"), community.maxTheoretical());
        assertEquals(new BigDecimal("56.11"), community.matchScore());
        assertFalse(community.sectorBonusApplied());

        // --- Livelihood Training (rank 3) ------------------------------------
        // Livelihood 3.50 × 5.0 × 1.25 = 21.875 -> 21.88
        // Education  2.00 × 2.0 × 0.75 =  3.00          -> raw 24.88
        // max = (5×5×1.5) + (5×2×1.5) = 37.50 + 15.00 = 52.50
        // MSMEs does not intersect {Women} -> no bonus
        // score = 24.88 × 100 / 52.50 = 47.3904... -> 47.39
        ProgramTypeScore livelihood = scores.get(2);
        assertEquals("Livelihood Training", livelihood.programTypeName());
        assertEquals(3, livelihood.rank());
        assertEquals(new BigDecimal("24.88"), livelihood.rawScore());
        assertEquals(new BigDecimal("52.50"), livelihood.maxTheoretical());
        assertEquals(new BigDecimal("47.39"), livelihood.matchScore());
        assertFalse(livelihood.sectorBonusApplied(), "MSMEs is not one of the community's sectors");

        // --- Sports Festival (rank 4) ----------------------------------------
        // Nothing mapped: no participating category, so there is nothing to normalize against.
        ProgramTypeScore sports = scores.get(3);
        assertEquals("Sports Festival", sports.programTypeName());
        assertEquals(4, sports.rank());
        assertEquals(new BigDecimal("0.00"), sports.matchScore());
        assertTrue(sports.contributions().isEmpty());

        // The narrow, well-aimed type outranks the broad one — the point of per-type normalization.
        assertTrue(health.matchScore().compareTo(community.matchScore()) > 0);
    }

    @Test
    void perfectFitWithSectorBonusIsCappedAtOneHundred() {
        ProgramType type = programType("pt-health", "Health Caravan", WOMEN);

        // Health critical at the maximum 5.00 -> raw == max, so the bonus pushes the ratio to 110.
        // raw = 5.00 × 5.0 × 1.5 = 37.50 ; max = 37.50 ; bonus = 3.75
        // (37.50 + 3.75) × 100 / 37.50 = 110.00 -> capped
        List<ProgramTypeScore> scores = scoring.score(
                List.of(result(HEALTH, "5.00", "critical", 1)),
                List.of(type),
                List.of(weight(type, HEALTH, "5.0")),
                Set.of(WOMEN.getId()));

        assertEquals(new BigDecimal("100.00"), scores.get(0).matchScore());
        assertEquals(new BigDecimal("37.50"), scores.get(0).rawScore());
        assertEquals(new BigDecimal("3.75"), scores.get(0).sectorBonus());
    }

    @Test
    void sectorBonusIsTheOnlyDifferenceBetweenOtherwiseIdenticalRuns() {
        ProgramType type = programType("pt-health", "Health Caravan", WOMEN);
        List<ProgramTypeNeedWeight> weights = List.of(weight(type, HEALTH, "5.0"));

        // Without the community serving Women: 33.75 × 100 / 37.50 = exactly 90.00
        ProgramTypeScore without = scoring.score(results(), List.of(type), weights, Set.of()).get(0);
        assertEquals(new BigDecimal("90.00"), without.matchScore());
        assertEquals(new BigDecimal("0.00"), without.sectorBonus());
        assertFalse(without.sectorBonusApplied());

        ProgramTypeScore with =
                scoring.score(results(), List.of(type), weights, Set.of(WOMEN.getId())).get(0);
        assertEquals(new BigDecimal("99.01"), with.matchScore());
        assertTrue(with.matchScore().compareTo(without.matchScore()) > 0);
    }

    @Test
    void breakdownReproducesTheRawScoreExactly() {
        ProgramType type = programType("pt-community", "Community Development");
        List<ProgramTypeNeedWeight> weights = List.of(
                weight(type, HEALTH, "2.0"),
                weight(type, LIVELIHOOD, "2.0"),
                weight(type, EDUCATION, "2.0"));

        ProgramTypeScore score = scoring.score(results(), List.of(type), weights, Set.of()).get(0);

        // AC 5: every displayed score must be reproducible from the breakdown. Contributions are
        // rounded before summing, so this is exact equality, not "close enough".
        BigDecimal summed = score.contributions().stream()
                .map(CategoryContribution::contribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(score.rawScore(), summed);

        // And each contribution is avg × weight × multiplier as advertised.
        CategoryContribution healthRow = score.contributions().stream()
                .filter(row -> "Health".equals(row.needCategoryName()))
                .findFirst()
                .orElseThrow();
        assertEquals(new BigDecimal("13.50"), healthRow.contribution());
        assertEquals(new BigDecimal("1.5"), healthRow.multiplier());
        assertEquals("critical", healthRow.priority());
    }

    @Test
    void categoriesOutsideTheMatrixDoNotDragTheScoreDown() {
        // The type addresses Health only; the survey also assessed Livelihood and Education.
        // Those must not enter max, or the type would be punished for questions it never claimed.
        ProgramType type = programType("pt-health", "Health Caravan");
        ProgramTypeScore score = scoring
                .score(results(), List.of(type), List.of(weight(type, HEALTH, "5.0")), Set.of())
                .get(0);

        assertEquals(1, score.contributions().size());
        assertEquals(new BigDecimal("37.50"), score.maxTheoretical(), "Health only, not all three");
    }

    /**
     * Documents a consequence of normalizing per program type that surprises people: because
     * {@code max} is built from the same weights as {@code raw}, uniformly scaling a type's weights
     * cancels out. Weight changes move a score by shifting the balance between categories — or by
     * hitting 0, which drops the category entirely.
     */
    @Test
    void uniformlyScalingWeightsDoesNotMoveTheScoreButChangingTheBalanceDoes() {
        ProgramType type = programType("pt-health", "Health Caravan");

        BigDecimal atFive = scoring
                .score(results(), List.of(type), List.of(weight(type, HEALTH, "5.0")), Set.of())
                .get(0).matchScore();
        BigDecimal atOne = scoring
                .score(results(), List.of(type), List.of(weight(type, HEALTH, "1.0")), Set.of())
                .get(0).matchScore();
        assertEquals(atFive, atOne, "a single participating weight scales raw and max alike");

        // Adding a second, lower-priority category shifts the balance and does move the score.
        BigDecimal balanced = scoring.score(results(), List.of(type),
                        List.of(weight(type, HEALTH, "5.0"), weight(type, EDUCATION, "5.0")), Set.of())
                .get(0).matchScore();
        assertTrue(balanced.compareTo(atFive) < 0,
                "taking on a weakly-scoring need dilutes the fit");

        // Dropping to zero removes the category from the calculation altogether.
        BigDecimal zeroed = scoring
                .score(results(), List.of(type), List.of(weight(type, HEALTH, "0.0")), Set.of())
                .get(0).matchScore();
        assertEquals(0, zeroed.compareTo(BigDecimal.ZERO));
    }

    @Test
    void tiesBreakByProgramTypeNameSoRunsAreReproducible() {
        ProgramType zebra = programType("pt-z", "Zebra Program");
        ProgramType alpha = programType("pt-a", "Alpha Program");

        List<ProgramTypeScore> scores = scoring.score(
                results(),
                List.of(zebra, alpha),
                List.of(weight(zebra, HEALTH, "3.0"), weight(alpha, HEALTH, "3.0")),
                Set.of());

        assertEquals(scores.get(0).matchScore(), scores.get(1).matchScore(), "identical weights tie");
        assertEquals("Alpha Program", scores.get(0).programTypeName());
        assertEquals("Zebra Program", scores.get(1).programTypeName());
    }

    @Test
    void priorityMultipliersFollowTheSpec() {
        assertEquals(new BigDecimal("1.5"), scoring.multiplierFor("critical"));
        assertEquals(new BigDecimal("1.25"), scoring.multiplierFor("high"));
        assertEquals(new BigDecimal("1.0"), scoring.multiplierFor("moderate"));
        assertEquals(new BigDecimal("0.75"), scoring.multiplierFor("low"));
        assertEquals(new BigDecimal("0.75"), scoring.multiplierFor(null), "unknown bands score lowest");
    }

    @Test
    void noResultsOrNoCandidatesProducesNoScores() {
        ProgramType type = programType("pt-health", "Health Caravan");
        assertTrue(scoring.score(List.of(), List.of(type), List.of(), Set.of()).get(0)
                .matchScore().compareTo(BigDecimal.ZERO) == 0);
        assertTrue(scoring.score(results(), List.of(), List.of(), Set.of()).isEmpty());
    }

    // --- fixture builders ---

    private static NeedCategory category(String id, String name) {
        NeedCategory category = new NeedCategory();
        category.setId(id);
        category.setName(name);
        return category;
    }

    private static Sector sector(String id, String name) {
        Sector sector = new Sector();
        sector.setId(id);
        sector.setName(name);
        return sector;
    }

    private static AssessmentResult result(NeedCategory category, String avg, String priority, int rank) {
        AssessmentResult result = new AssessmentResult();
        result.setNeedCategory(category);
        result.setAvgScore(new BigDecimal(avg));
        result.setPriority(priority);
        result.setRankPosition(rank);
        return result;
    }

    private static ProgramType programType(String id, String name, Sector... sectors) {
        ProgramType programType = new ProgramType();
        programType.setId(id);
        programType.setName(name);
        programType.setSectors(Set.of(sectors));
        return programType;
    }

    private static ProgramTypeNeedWeight weight(ProgramType type, NeedCategory category, String weight) {
        ProgramTypeNeedWeight row = new ProgramTypeNeedWeight();
        row.setProgramType(type);
        row.setNeedCategory(category);
        row.setWeight(new BigDecimal(weight));
        return row;
    }
}
