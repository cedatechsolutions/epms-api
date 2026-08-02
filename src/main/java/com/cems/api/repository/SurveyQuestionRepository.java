package com.cems.api.repository;

import com.cems.api.entity.SurveyQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SurveyQuestionRepository extends JpaRepository<SurveyQuestion, String> {

    List<SurveyQuestion> findBySurveyIdOrderByOrderIndexAsc(String surveyId);

    Optional<SurveyQuestion> findByIdAndSurveyId(String id, String surveyId);

    long countBySurveyId(String surveyId);
}
