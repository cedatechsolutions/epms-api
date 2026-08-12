package com.cems.api.repository;

import com.cems.api.entity.Program;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProgramRepository
        extends JpaRepository<Program, String>, JpaSpecificationExecutor<Program> {

    Optional<Program> findByIdAndDeletedAtIsNull(String id);

    /**
     * Backs the community delete-block (spec Module 2 AC → 409): a community may not be deleted
     * while it still has non-cancelled programs.
     */
    boolean existsByCommunityIdAndDeletedAtIsNullAndStatusNot(String communityId, String status);

    /** Backs the community history timeline; newest proposal first. */
    List<Program> findByCommunityIdAndDeletedAtIsNullOrderByCreatedAtDesc(String communityId);

    /** The proposal spawned by accepting a recommendation, if it still exists. */
    Optional<Program> findByRecommendationIdAndDeletedAtIsNull(String recommendationId);

    /**
     * Recommendation → spawned proposal ids for a whole result set, so the recommendations screen
     * can link to proposals without a query per card. Returns rows of {@code [recommendationId, programId]}.
     */
    @Query("""
            SELECT p.recommendation.id, p.id
            FROM Program p
            WHERE p.deletedAt IS NULL
              AND p.recommendation.id IN :recommendationIds
            """)
    List<Object[]> findProgramIdsByRecommendationIds(
            @Param("recommendationIds") Collection<String> recommendationIds);

    /**
     * Status counts for the list screen's tab badges, in one query rather than nine.
     * Returns rows of {@code [status, count]}.
     */
    @Query("""
            SELECT p.status, COUNT(p)
            FROM Program p
            WHERE p.deletedAt IS NULL
              AND (:ownerId IS NULL OR p.createdBy = :ownerId OR p.facultyLeadId = :ownerId)
            GROUP BY p.status
            """)
    List<Object[]> countByStatusForOwner(@Param("ownerId") String ownerId);

    /**
     * Active (non-cancelled, non-deleted) program counts for a page of communities, in one query
     * rather than one per row. Returns rows of {@code [communityId, count]}.
     */
    @Query("""
            SELECT p.community.id, COUNT(p)
            FROM Program p
            WHERE p.deletedAt IS NULL
              AND p.status <> 'cancelled'
              AND p.community.id IN :communityIds
            GROUP BY p.community.id
            """)
    List<Object[]> countActiveByCommunityIds(@Param("communityIds") Collection<String> communityIds);
}
