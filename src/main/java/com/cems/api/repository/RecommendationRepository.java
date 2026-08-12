package com.cems.api.repository;

import com.cems.api.entity.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, String> {

    List<Recommendation> findBySurveyIdOrderByRankPositionAsc(String surveyId);

    /** Re-running the engine replaces these; decided rows are kept (Module 4 AC 1). */
    List<Recommendation> findBySurveyIdAndStatus(String surveyId, String status);

    boolean existsBySurveyId(String surveyId);

    /**
     * Decision backlog for the dashboard, excluding recommendations belonging to a soft-deleted
     * survey. Returns rows of {@code [status, count]}.
     */
    @Query("""
            SELECT r.status, COUNT(r)
            FROM Recommendation r
            WHERE r.survey.deletedAt IS NULL
            GROUP BY r.status
            """)
    List<Object[]> countByStatus();
}
