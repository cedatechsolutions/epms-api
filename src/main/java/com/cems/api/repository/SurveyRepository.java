package com.cems.api.repository;

import com.cems.api.entity.Survey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SurveyRepository
        extends JpaRepository<Survey, String>, JpaSpecificationExecutor<Survey> {

    Optional<Survey> findByIdAndDeletedAtIsNull(String id);

    Optional<Survey> findByAccessTokenAndDeletedAtIsNull(String accessToken);

    long countByDeletedAtIsNull();
}
