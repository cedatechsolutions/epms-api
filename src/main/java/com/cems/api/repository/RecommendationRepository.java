package com.cems.api.repository;

import com.cems.api.entity.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, String> {

    List<Recommendation> findBySurveyIdOrderByRankPositionAsc(String surveyId);

    /** Re-running the engine replaces these; decided rows are kept (Module 4 AC 1). */
    List<Recommendation> findBySurveyIdAndStatus(String surveyId, String status);

    boolean existsBySurveyId(String surveyId);
}
