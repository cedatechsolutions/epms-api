package com.cems.api.repository;

import com.cems.api.entity.SurveyResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, String> {

    List<SurveyResponse> findBySurveyId(String surveyId);

    long countBySurveyId(String surveyId);

    boolean existsBySurveyIdAndRespondentToken(String surveyId, String respondentToken);

    long countBySurveyIdAndRespondentSex(String surveyId, String respondentSex);

    /**
     * System-wide respondent counts by sex for the dashboard, excluding responses whose survey has
     * been soft-deleted. Returns rows of {@code [respondentSex, count]}.
     */
    @Query("""
            SELECT r.respondentSex, COUNT(r)
            FROM SurveyResponse r
            WHERE r.survey.deletedAt IS NULL
            GROUP BY r.respondentSex
            """)
    List<Object[]> countByRespondentSex();
}
