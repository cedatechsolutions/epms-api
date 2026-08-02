package com.cems.api.repository;

import com.cems.api.entity.SurveyResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, String> {

    List<SurveyResponse> findBySurveyId(String surveyId);

    long countBySurveyId(String surveyId);

    boolean existsBySurveyIdAndRespondentToken(String surveyId, String respondentToken);

    long countBySurveyIdAndRespondentSex(String surveyId, String respondentSex);
}
