package com.cems.api.repository;

import com.cems.api.entity.Evaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EvaluationRepository extends JpaRepository<Evaluation, String> {

    Optional<Evaluation> findByIdAndProgramActivityId(String id, String programActivityId);

    List<Evaluation> findByProgramActivityIdOrderByCreatedAtAsc(String programActivityId);

    /** Evaluations across a set of activities, for the activities tab without a query per row. */
    List<Evaluation> findByProgramActivityIdInOrderByCreatedAtAsc(Collection<String> activityIds);

    /**
     * Backs the completion precondition (spec Module 5 AC 5): a program may only be marked completed
     * once at least one POST evaluation exists anywhere on it. Deleted activities do not count.
     */
    @Query("""
            SELECT COUNT(e)
            FROM Evaluation e
            WHERE e.programActivity.deletedAt IS NULL
              AND e.programActivity.program.id = :programId
              AND e.evalType = 'post'
            """)
    long countPostEvaluationsForProgram(@Param("programId") String programId);

    /**
     * Pre/post evaluation presence per program for the dashboard's completion table (spec Module 6
     * §4), in one query rather than two per row. Returns rows of {@code [programId, evalType, count]};
     * the service only asks whether the count is non-zero, but carrying it costs nothing and makes
     * the row auditable.
     */
    @Query("""
            SELECT e.programActivity.program.id, e.evalType, COUNT(e)
            FROM Evaluation e
            WHERE e.programActivity.deletedAt IS NULL
              AND e.programActivity.program.id IN :programIds
            GROUP BY e.programActivity.program.id, e.evalType
            """)
    List<Object[]> countByTypeForPrograms(@Param("programIds") Collection<String> programIds);

    void deleteByProgramActivityId(String programActivityId);
}
