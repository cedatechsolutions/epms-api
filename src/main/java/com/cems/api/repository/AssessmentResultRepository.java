package com.cems.api.repository;

import com.cems.api.entity.AssessmentResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssessmentResultRepository extends JpaRepository<AssessmentResult, String> {

    List<AssessmentResult> findBySurveyIdOrderByRankPositionAsc(String surveyId);

    boolean existsBySurveyId(String surveyId);

    /** How many surveys have had their results finalized (one snapshot row set per survey). */
    @Query("""
            SELECT COUNT(DISTINCT a.survey.id)
            FROM AssessmentResult a
            WHERE a.survey.deletedAt IS NULL
            """)
    long countFinalizedSurveys();

    /**
     * Need categories rolled up across every finalized assessment, strongest first — the dashboard's
     * "top identified needs" table. Returns rows of
     * {@code [categoryId, categoryName, avgScore, assessmentCount, responseCount, femaleCount, maleCount]}.
     */
    @Query("""
            SELECT a.needCategory.id,
                   a.needCategory.name,
                   AVG(a.avgScore),
                   COUNT(DISTINCT a.survey.id),
                   COALESCE(SUM(a.responseCount), 0),
                   COALESCE(SUM(a.femaleCount), 0),
                   COALESCE(SUM(a.maleCount), 0)
            FROM AssessmentResult a
            WHERE a.survey.deletedAt IS NULL
            GROUP BY a.needCategory.id, a.needCategory.name
            ORDER BY AVG(a.avgScore) DESC
            """)
    List<Object[]> aggregateByNeedCategory();
}
