package com.cems.api.repository;

import com.cems.api.entity.Survey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SurveyRepository
        extends JpaRepository<Survey, String>, JpaSpecificationExecutor<Survey> {

    Optional<Survey> findByIdAndDeletedAtIsNull(String id);

    Optional<Survey> findByAccessTokenAndDeletedAtIsNull(String accessToken);

    long countByDeletedAtIsNull();

    /**
     * Draft/deployed/closed counts for the dashboard in one query rather than three.
     * Returns rows of {@code [status, count]}.
     */
    @Query("""
            SELECT s.status, COUNT(s)
            FROM Survey s
            WHERE s.deletedAt IS NULL
            GROUP BY s.status
            """)
    List<Object[]> countByStatus();
}
