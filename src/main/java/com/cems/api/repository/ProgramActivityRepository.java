package com.cems.api.repository;

import com.cems.api.entity.ProgramActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProgramActivityRepository extends JpaRepository<ProgramActivity, String> {

    Optional<ProgramActivity> findByIdAndDeletedAtIsNull(String id);

    /** The activities tab, oldest session first — the order they are delivered in. */
    List<ProgramActivity> findByProgramIdAndDeletedAtIsNullOrderByActivityDateAscStartTimeAsc(String programId);

    long countByProgramIdAndDeletedAtIsNull(String programId);

    long countByProgramIdAndDeletedAtIsNullAndStatus(String programId, String status);

    /**
     * Activities on a program that are neither done nor cancelled. The completion rule is "every
     * activity settled", so this returning zero is the condition — expressed as a count so the check
     * costs one query rather than loading the whole list.
     */
    @Query("""
            SELECT COUNT(a)
            FROM ProgramActivity a
            WHERE a.deletedAt IS NULL
              AND a.program.id = :programId
              AND a.status NOT IN ('done', 'cancelled')
            """)
    long countUnsettledByProgramId(@Param("programId") String programId);

    /**
     * Activity counts by status for a set of programs, in one query rather than one per program.
     * Returns rows of {@code [programId, status, count]}. Backs the program list's activity column
     * and the Phase 6 dashboard.
     */
    @Query("""
            SELECT a.program.id, a.status, COUNT(a)
            FROM ProgramActivity a
            WHERE a.deletedAt IS NULL
              AND a.program.id IN :programIds
            GROUP BY a.program.id, a.status
            """)
    List<Object[]> countByStatusForPrograms(@Param("programIds") java.util.Collection<String> programIds);
}
