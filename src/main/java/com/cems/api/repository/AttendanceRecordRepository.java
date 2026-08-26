package com.cems.api.repository;

import com.cems.api.entity.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, String> {

    Optional<AttendanceRecord> findByIdAndProgramActivityId(String id, String programActivityId);

    List<AttendanceRecord> findByProgramActivityIdOrderByAttendeeNameAsc(String programActivityId);

    long countByProgramActivityId(String programActivityId);

    /**
     * Sex counts for a set of activities, in one query rather than one per activity.
     * Returns rows of {@code [activityId, sex, count]} — the running Total/F/M the spec requires on
     * every activity row and on the program header (Module 5 AC 4).
     */
    @Query("""
            SELECT r.programActivity.id, r.sex, COUNT(r)
            FROM AttendanceRecord r
            WHERE r.programActivity.id IN :activityIds
            GROUP BY r.programActivity.id, r.sex
            """)
    List<Object[]> countBySexForActivities(@Param("activityIds") Collection<String> activityIds);

    /**
     * Program-level sex counts. Deleted activities are excluded so the header total agrees with the
     * rows actually shown. Returns rows of {@code [sex, count]}.
     */
    @Query("""
            SELECT r.sex, COUNT(r)
            FROM AttendanceRecord r
            WHERE r.programActivity.deletedAt IS NULL
              AND r.programActivity.program.id = :programId
            GROUP BY r.sex
            """)
    List<Object[]> countBySexForProgram(@Param("programId") String programId);

    // --- M&E dashboard aggregates (spec Module 6) -------------------------------------------
    //
    // Beneficiaries are counted for the PROGRAM that drew them, using the same period clause the
    // program aggregates use (proposed_date in range), not the date the session physically fell on.
    // See the V12 migration header: one anchor keeps every KPI equal to the list behind it.
    //
    // Deleted activities and soft-deleted programs are excluded everywhere, so a withdrawn proposal
    // cannot leave phantom beneficiaries in the campus total.

    /**
     * "Beneficiaries reached", sex-disaggregated. Returns rows of {@code [sex, count]}.
     *
     * <p>These are raw attendance rows: one person at three sessions counts three times. That is the
     * v1 method the spec allows, and the client states it on the card rather than letting the figure
     * pass as a headcount.
     */
    @Query("""
            SELECT r.sex, COUNT(r)
            FROM AttendanceRecord r
            WHERE r.programActivity.deletedAt IS NULL
              AND r.programActivity.program.deletedAt IS NULL
              AND (:startsOn IS NULL
                   OR (r.programActivity.program.proposedDate IS NOT NULL
                       AND r.programActivity.program.proposedDate BETWEEN :startsOn AND :endsOn))
            GROUP BY r.sex
            """)
    List<Object[]> countBySexForPeriod(@Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn);

    /**
     * The "beneficiaries by sector" chart, disaggregated (cross-cutting rule 1: every count surface
     * shows Total/F/M). Returns rows of {@code [sectorId, sectorName, sex, count]}.
     *
     * <p>LEFT JOIN on purpose — attendees recorded without a sector are real beneficiaries and must
     * still reach the chart, where the service buckets them under an explicit "Not specified" row.
     * An inner join would silently drop them and make the chart disagree with the KPI above it.
     */
    @Query("""
            SELECT s.id, s.name, r.sex, COUNT(r)
            FROM AttendanceRecord r
            LEFT JOIN r.sector s
            WHERE r.programActivity.deletedAt IS NULL
              AND r.programActivity.program.deletedAt IS NULL
              AND (:startsOn IS NULL
                   OR (r.programActivity.program.proposedDate IS NOT NULL
                       AND r.programActivity.program.proposedDate BETWEEN :startsOn AND :endsOn))
            GROUP BY s.id, s.name, r.sex
            """)
    List<Object[]> countBySectorAndSexForPeriod(@Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn);

    /**
     * Actual attendance per program for the completion table, in one query rather than one per row.
     * Returns rows of {@code [programId, sex, count]}.
     */
    @Query("""
            SELECT r.programActivity.program.id, r.sex, COUNT(r)
            FROM AttendanceRecord r
            WHERE r.programActivity.deletedAt IS NULL
              AND r.programActivity.program.id IN :programIds
            GROUP BY r.programActivity.program.id, r.sex
            """)
    List<Object[]> countBySexForPrograms(@Param("programIds") Collection<String> programIds);

    void deleteByProgramActivityId(String programActivityId);
}
