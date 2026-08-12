package com.cems.api.repository;

import com.cems.api.entity.Community;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommunityRepository
        extends JpaRepository<Community, String>, JpaSpecificationExecutor<Community> {

    Optional<Community> findByIdAndDeletedAtIsNull(String id);

    long countByDeletedAtIsNull();

    /**
     * How many profiles carry a full female/male split — the denominator the dashboard shows next to
     * the GAD population figures, since a community may be profiled without one.
     */
    long countByDeletedAtIsNullAndPopulationFemaleIsNotNullAndPopulationMaleIsNotNull();

    /**
     * Profiled reach across all live communities, in one query for the dashboard.
     * Returns a single row of {@code [population, populationFemale, populationMale, households]};
     * the {@code COALESCE} keeps it a row of zeros when nothing is profiled yet.
     */
    @Query("""
            SELECT COALESCE(SUM(c.estimatedPopulation), 0),
                   COALESCE(SUM(c.populationFemale), 0),
                   COALESCE(SUM(c.populationMale), 0),
                   COALESCE(SUM(c.householdCount), 0)
            FROM Community c
            WHERE c.deletedAt IS NULL
            """)
    List<Object[]> sumProfileTotals();
}
