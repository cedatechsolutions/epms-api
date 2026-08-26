package com.cems.api.repository;

import com.cems.api.entity.Program;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
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
     *
     * <p>The visibility clause must mirror {@code buildSpecification}'s exactly — created, led, or
     * assigned — or the tab badges would disagree with the rows beneath them. {@code assignedIds}
     * is never empty when passed (callers substitute a sentinel), because an empty IN list is
     * invalid SQL.
     *
     * <p>The period clause is here for the same reason: arriving from a dashboard drill-down applies
     * a period to the rows, and a badge counting all time above a list showing one semester is worse
     * than no badge at all. Null {@code startsOn} means every period.
     */
    @Query("""
            SELECT p.status, COUNT(p)
            FROM Program p
            WHERE p.deletedAt IS NULL
              AND (:ownerId IS NULL
                   OR p.createdBy = :ownerId
                   OR p.facultyLeadId = :ownerId
                   OR p.id IN :assignedIds)
              AND (:startsOn IS NULL
                   OR (p.proposedDate IS NOT NULL AND p.proposedDate BETWEEN :startsOn AND :endsOn))
            GROUP BY p.status
            """)
    List<Object[]> countByStatusForOwner(@Param("ownerId") String ownerId,
            @Param("assignedIds") Collection<String> assignedIds,
            @Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn);

    // --- M&E dashboard aggregates (spec Module 6) -------------------------------------------
    //
    // Every query below shares one period clause:
    //
    //     :startsOn IS NULL  ->  all periods, including programs with no proposed date
    //     otherwise          ->  proposed_date BETWEEN :startsOn AND :endsOn
    //
    // It is repeated verbatim rather than factored into a Specification because these are grouped
    // aggregates, not row fetches, and because the programs list applies the SAME rule through its
    // Specification — if the two ever have to change, they must change together and a reader has to
    // be able to see both. See the V12 migration header for why the anchor is proposed_date alone.

    /**
     * Status counts for the selected period. Returns rows of {@code [status, count]} — the source of
     * both "programs completed" and "programs total" on the KPI row.
     */
    @Query("""
            SELECT p.status, COUNT(p)
            FROM Program p
            WHERE p.deletedAt IS NULL
              AND (:startsOn IS NULL
                   OR (p.proposedDate IS NOT NULL AND p.proposedDate BETWEEN :startsOn AND :endsOn))
            GROUP BY p.status
            """)
    List<Object[]> countByStatusForPeriod(@Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn);

    /**
     * The "programs by type" chart. Returns rows of {@code [programTypeId, programTypeName, count]},
     * biggest bar first. Proposals with no type yet (drafts) are excluded — the chart plots the type
     * library, and a nameless bar is not a category.
     */
    @Query("""
            SELECT t.id, t.name, COUNT(p)
            FROM Program p
            JOIN p.programType t
            WHERE p.deletedAt IS NULL
              AND (:startsOn IS NULL
                   OR (p.proposedDate IS NOT NULL AND p.proposedDate BETWEEN :startsOn AND :endsOn))
            GROUP BY t.id, t.name
            ORDER BY COUNT(p) DESC, t.name ASC
            """)
    List<Object[]> countByProgramTypeForPeriod(@Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn);

    /**
     * "Communities served" — distinct communities with at least one activity actually delivered
     * (spec Module 6 §2). A community with only scheduled sessions has not been served yet, so the
     * EXISTS clause requires a {@code done} activity rather than merely a program.
     */
    @Query("""
            SELECT COUNT(DISTINCT p.community.id)
            FROM Program p
            WHERE p.deletedAt IS NULL
              AND p.community IS NOT NULL
              AND (:startsOn IS NULL
                   OR (p.proposedDate IS NOT NULL AND p.proposedDate BETWEEN :startsOn AND :endsOn))
              AND EXISTS (SELECT 1 FROM ProgramActivity a
                          WHERE a.program = p AND a.deletedAt IS NULL AND a.status = 'done')
            """)
    long countCommunitiesServedForPeriod(@Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn);

    /**
     * "Faculty involved" — distinct faculty leads on programs that reached approval (spec Module 6
     * §2: "active/completed programs"). Drafts and proposals still in the chain do not count: nobody
     * has been involved in extension work that has not been approved to happen.
     */
    @Query("""
            SELECT COUNT(DISTINCT p.facultyLeadId)
            FROM Program p
            WHERE p.deletedAt IS NULL
              AND p.facultyLeadId IS NOT NULL
              AND p.status IN ('approved', 'ongoing', 'completed')
              AND (:startsOn IS NULL
                   OR (p.proposedDate IS NOT NULL AND p.proposedDate BETWEEN :startsOn AND :endsOn))
            """)
    long countFacultyInvolvedForPeriod(@Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn);

    /**
     * The programs behind the completion table, newest proposed date first. Fetches community and
     * type eagerly because every row renders both; the per-program attendance and evaluation figures
     * are then batched by id rather than walked per row.
     */
    @Query("""
            SELECT p
            FROM Program p
            LEFT JOIN FETCH p.community
            LEFT JOIN FETCH p.programType
            WHERE p.deletedAt IS NULL
              AND (:startsOn IS NULL
                   OR (p.proposedDate IS NOT NULL AND p.proposedDate BETWEEN :startsOn AND :endsOn))
            ORDER BY p.proposedDate DESC NULLS LAST, p.title ASC
            """)
    List<Program> findForPeriod(@Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn,
            Pageable pageable);

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
