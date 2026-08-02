package com.cems.api.service;

import com.cems.api.entity.NeedCategory;
import com.cems.api.entity.SurveyAnswer;
import com.cems.api.entity.SurveyQuestion;
import com.cems.api.entity.SurveyResponse;
import com.cems.api.service.AssessmentScoringService.CategoryScore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §5.4 MANDATORY test: a fixed fixture of responses must produce exactly the hand-computed
 * scores, sex-disaggregated counts, priorities and ranks. Every expected value below is derived by
 * hand from the spec formula
 * {@code avg = Σ(value × weight) / Σ(weight)} over rating answers in the category.
 */
class AssessmentScoringServiceTest {

    private final AssessmentScoringService scoring = new AssessmentScoringService(
            new BigDecimal("4.5"), new BigDecimal("3.5"), new BigDecimal("2.5"));

    private static final NeedCategory HEALTH = category("cat-health", "Health");
    private static final NeedCategory LIVELIHOOD = category("cat-livelihood", "Livelihood");
    private static final NeedCategory EDUCATION = category("cat-education", "Education");

    @Test
    void producesHandComputedScoresRanksAndSexDisaggregatedCounts() {
        // --- Questions -------------------------------------------------------
        // Health has two rating questions with different weights; Livelihood one; Education one.
        SurveyQuestion healthA = rating("q-h1", HEALTH, "3.0");
        SurveyQuestion healthB = rating("q-h2", HEALTH, "1.0");
        SurveyQuestion livelihood = rating("q-l1", LIVELIHOOD, "2.0");
        SurveyQuestion education = rating("q-e1", EDUCATION, "1.0");
        // Not scored in v1: an open-text question, and a rating question with no category.
        SurveyQuestion openText = question("q-o1", HEALTH, "open_text", "5.0");
        SurveyQuestion uncategorized = rating("q-u1", null, "5.0");

        // --- Respondents -----------------------------------------------------
        SurveyResponse f1 = response("r-f1", SurveyResponse.SEX_FEMALE);
        SurveyResponse f2 = response("r-f2", SurveyResponse.SEX_FEMALE);
        SurveyResponse m1 = response("r-m1", SurveyResponse.SEX_MALE);
        SurveyResponse x1 = response("r-x1", SurveyResponse.SEX_PREFER_NOT_TO_SAY);

        List<SurveyAnswer> answers = new ArrayList<>();

        // Health, weight 3.0: f1=5, f2=4, m1=5, x1=4
        answers.add(answer(healthA, f1, "5"));
        answers.add(answer(healthA, f2, "4"));
        answers.add(answer(healthA, m1, "5"));
        answers.add(answer(healthA, x1, "4"));
        // Health, weight 1.0: f1=4, m1=5
        answers.add(answer(healthB, f1, "4"));
        answers.add(answer(healthB, m1, "5"));

        // Livelihood, weight 2.0: f2=3, m1=4
        answers.add(answer(livelihood, f2, "3"));
        answers.add(answer(livelihood, m1, "4"));

        // Education, weight 1.0: f1=2, f2=2
        answers.add(answer(education, f1, "2"));
        answers.add(answer(education, f2, "2"));

        // Ignored by scoring: open text in Health, and an uncategorized rating.
        answers.add(answer(openText, f1, "Clinic is far"));
        answers.add(answer(uncategorized, f1, "5"));

        List<CategoryScore> scores = scoring.score(answers);

        assertEquals(3, scores.size(), "only categories with rating answers are scored");

        // --- Health ----------------------------------------------------------
        // numerator   = (5+4+5+4)×3.0  + (4+5)×1.0 = 18×3 + 9×1 = 54 + 9 = 63
        // denominator = 4×3.0 + 2×1.0             = 12 + 2      = 14
        // avg = 63 / 14 = 4.50  -> critical (>= 4.5), rank 1
        CategoryScore health = scores.get(0);
        assertEquals("Health", health.needCategoryName());
        assertEquals(new BigDecimal("4.50"), health.avgScore());
        assertEquals("critical", health.priority());
        assertEquals(1, health.rank());
        assertEquals(4, health.responseCount(), "f1, f2, m1, x1 all answered a Health question");
        assertEquals(2, health.femaleCount());
        assertEquals(1, health.maleCount());
        // GAD: "prefer not to say" is counted in the total but in neither sex tally.
        assertTrue(health.femaleCount() + health.maleCount() < health.responseCount());

        // --- Livelihood ------------------------------------------------------
        // numerator = (3+4)×2.0 = 14 ; denominator = 2×2.0 = 4 ; avg = 3.50 -> high, rank 2
        CategoryScore livelihoodScore = scores.get(1);
        assertEquals("Livelihood", livelihoodScore.needCategoryName());
        assertEquals(new BigDecimal("3.50"), livelihoodScore.avgScore());
        assertEquals("high", livelihoodScore.priority());
        assertEquals(2, livelihoodScore.rank());
        assertEquals(2, livelihoodScore.responseCount());
        assertEquals(1, livelihoodScore.femaleCount());
        assertEquals(1, livelihoodScore.maleCount());

        // --- Education -------------------------------------------------------
        // numerator = (2+2)×1.0 = 4 ; denominator = 2×1.0 = 2 ; avg = 2.00 -> low, rank 3
        CategoryScore educationScore = scores.get(2);
        assertEquals("Education", educationScore.needCategoryName());
        assertEquals(new BigDecimal("2.00"), educationScore.avgScore());
        assertEquals("low", educationScore.priority());
        assertEquals(3, educationScore.rank());
        assertEquals(2, educationScore.responseCount());
        assertEquals(2, educationScore.femaleCount());
        assertEquals(0, educationScore.maleCount());
    }

    @Test
    void priorityBandsFollowTheSpecThresholds() {
        assertEquals("critical", scoring.priorityFor(new BigDecimal("4.50")));
        assertEquals("critical", scoring.priorityFor(new BigDecimal("5.00")));
        assertEquals("high", scoring.priorityFor(new BigDecimal("4.49")));
        assertEquals("high", scoring.priorityFor(new BigDecimal("3.50")));
        assertEquals("moderate", scoring.priorityFor(new BigDecimal("3.49")));
        assertEquals("moderate", scoring.priorityFor(new BigDecimal("2.50")));
        assertEquals("low", scoring.priorityFor(new BigDecimal("2.49")));
        assertEquals("low", scoring.priorityFor(new BigDecimal("1.00")));
    }

    @Test
    void weightingActuallyChangesTheRanking() {
        // Same raw answers, but the heavier question drags its category's average up.
        SurveyQuestion heavy = rating("q-heavy", HEALTH, "5.0");
        SurveyQuestion light = rating("q-light", HEALTH, "0.5");
        SurveyResponse r1 = response("r-1", SurveyResponse.SEX_FEMALE);

        // avg = (5×5.0 + 1×0.5) / (5.0 + 0.5) = 25.5 / 5.5 = 4.64 -> critical
        List<CategoryScore> scores = scoring.score(List.of(
                answer(heavy, r1, "5"),
                answer(light, r1, "1")));

        assertEquals(new BigDecimal("4.64"), scores.get(0).avgScore());
        assertEquals("critical", scores.get(0).priority());
    }

    @Test
    void noRatingAnswersProducesNoScores() {
        SurveyQuestion openText = question("q-o", HEALTH, "open_text", "3.0");
        SurveyResponse r1 = response("r-1", SurveyResponse.SEX_MALE);

        assertTrue(scoring.score(List.of(answer(openText, r1, "some text"))).isEmpty());
        assertTrue(scoring.score(List.of()).isEmpty());
    }

    // --- fixture builders ---

    private static NeedCategory category(String id, String name) {
        NeedCategory category = new NeedCategory();
        category.setId(id);
        category.setName(name);
        return category;
    }

    private static SurveyQuestion rating(String id, NeedCategory category, String weight) {
        return question(id, category, "rating", weight);
    }

    private static SurveyQuestion question(String id, NeedCategory category, String type, String weight) {
        SurveyQuestion question = new SurveyQuestion();
        question.setId(id);
        question.setNeedCategory(category);
        question.setQuestionType(type);
        question.setWeight(new BigDecimal(weight));
        return question;
    }

    private static SurveyResponse response(String id, String sex) {
        SurveyResponse response = new SurveyResponse();
        response.setId(id);
        response.setRespondentSex(sex);
        return response;
    }

    private static SurveyAnswer answer(SurveyQuestion question, SurveyResponse response, String value) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setSurveyQuestion(question);
        answer.setSurveyResponse(response);
        answer.setAnswerValue(value);
        return answer;
    }
}
