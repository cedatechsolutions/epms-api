package com.cems.api.repository;

import com.cems.api.entity.SurveyAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SurveyAnswerRepository extends JpaRepository<SurveyAnswer, String> {

    /** All answers for a survey, with question + response eagerly joined (used by scoring in Pass C). */
    @Query("""
            select a from SurveyAnswer a
              join fetch a.surveyQuestion q
              join fetch a.surveyResponse r
            where r.survey.id = :surveyId
            """)
    List<SurveyAnswer> findAllBySurveyId(@Param("surveyId") String surveyId);
}
