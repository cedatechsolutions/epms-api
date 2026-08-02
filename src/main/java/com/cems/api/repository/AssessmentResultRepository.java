package com.cems.api.repository;

import com.cems.api.entity.AssessmentResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssessmentResultRepository extends JpaRepository<AssessmentResult, String> {

    List<AssessmentResult> findBySurveyIdOrderByRankPositionAsc(String surveyId);

    boolean existsBySurveyId(String surveyId);
}
