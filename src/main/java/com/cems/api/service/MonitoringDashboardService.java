package com.cems.api.service;

import com.cems.api.dto.AcademicPeriodResponse;
import com.cems.api.dto.MonitoringDashboardResponse;
import com.cems.api.dto.MonitoringDashboardResponse.CompletionRow;
import com.cems.api.dto.MonitoringDashboardResponse.Kpis;
import com.cems.api.dto.MonitoringDashboardResponse.SectorCount;
import com.cems.api.dto.MonitoringDashboardResponse.TypeCount;
import com.cems.api.entity.AcademicPeriod;
import com.cems.api.entity.AttendanceRecord;
import com.cems.api.entity.Evaluation;
import com.cems.api.entity.Program;
import com.cems.api.repository.AttendanceRecordRepository;
import com.cems.api.repository.EvaluationRepository;
import com.cems.api.repository.ProgramRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The M&amp;E dashboard aggregator (spec Module 6), behind {@code GET /api/dashboard}.
 *
 * <p>Separate from {@link DashboardService}, which stays the personal landing overview every role
 * gets. This one is campus-wide and coordinator/admin-only ({@code canViewMonitoringDashboard()});
 * the two are shown on the same screen but are not the same payload and never share a permission.
 *
 * <p><strong>Nine queries, fixed.</strong> Four period aggregates, one page of programs, two batched
 * per-program rollups, two chart aggregates — none of them inside a loop (spec Module 6 AC 6: "all
 * aggregates computed via SQL, no N+1"). Adding a widget means adding a query, never a walk over
 * {@code completion} rows.
 *
 * <p><strong>One period rule.</strong> A program belongs to a period when its {@code proposedDate}
 * falls inside it; its beneficiaries are counted with it wherever the session physically fell. The
 * clause lives in {@link ProgramRepository} and is applied identically by the programs list's
 * {@code periodId} filter, which is what makes every KPI here clickable through to a list showing
 * exactly that many rows.
 */
@Service
public class MonitoringDashboardService {

    /**
     * Cap on completion-table rows in one payload. A single semester never approaches this; "All
     * periods" on a mature database would, and shipping ten thousand rows to draw one table is not a
     * dashboard. {@code completionTotal} always reports the true count so the client can say so.
     */
    private static final int COMPLETION_ROW_LIMIT = 200;

    /** Bucket label for attendees recorded without a sector. Never dropped — see the chart query. */
    private static final String UNSPECIFIED_SECTOR = "Not specified";

    /**
     * The method note the spec requires on the beneficiaries card. Kept here, beside the query that
     * produces the number, so the UI and both exports quote one sentence rather than three drifting
     * paraphrases.
     */
    private static final String BENEFICIARY_METHOD =
            "Counts attendance records, not unique people: someone who attends three sessions of the "
                    + "same program is counted three times. Beneficiaries are credited to the program "
                    + "that drew them, using its proposed date to place it in a period.";

    private final ProgramRepository programRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final EvaluationRepository evaluationRepository;
    private final AcademicPeriodService academicPeriodService;

    public MonitoringDashboardService(ProgramRepository programRepository,
            AttendanceRecordRepository attendanceRepository,
            EvaluationRepository evaluationRepository,
            AcademicPeriodService academicPeriodService) {
        this.programRepository = programRepository;
        this.attendanceRepository = attendanceRepository;
        this.evaluationRepository = evaluationRepository;
        this.academicPeriodService = academicPeriodService;
    }

    /**
     * Builds the whole payload for one period.
     *
     * @param periodId a seeded period id, or null/blank for every period at once. An id that does
     *                 not exist raises 404 rather than quietly widening to all periods — see
     *                 {@link AcademicPeriodService#resolveRequested(String)}.
     */
    @Transactional(readOnly = true)
    public MonitoringDashboardResponse getDashboard(String periodId) {
        Optional<AcademicPeriod> period = academicPeriodService.resolveRequested(periodId);
        LocalDate startsOn = period.map(AcademicPeriod::getStartsOn).orElse(null);
        LocalDate endsOn = period.map(AcademicPeriod::getEndsOn).orElse(null);

        Map<String, Long> statusCounts = toCountMap(
                startsOn == null
                        ? programRepository.countByStatus()
                        : programRepository.countByStatusForPeriod(startsOn, endsOn));
        Map<String, Long> beneficiariesBySex = toCountMap(
                startsOn == null
                        ? attendanceRepository.countBySex()
                        : attendanceRepository.countBySexForPeriod(startsOn, endsOn));

        long female = beneficiariesBySex.getOrDefault(AttendanceRecord.SEX_FEMALE, 0L);
        long male = beneficiariesBySex.getOrDefault(AttendanceRecord.SEX_MALE, 0L);

        Kpis kpis = new Kpis(
                startsOn == null
                        ? programRepository.countCommunitiesServed()
                        : programRepository.countCommunitiesServedForPeriod(startsOn, endsOn),
                statusCounts.values().stream().mapToLong(Long::longValue).sum(),
                statusCounts.getOrDefault(Program.STATUS_COMPLETED, 0L),
                female + male,
                female,
                male,
                startsOn == null
                        ? programRepository.countFacultyInvolved()
                        : programRepository.countFacultyInvolvedForPeriod(startsOn, endsOn),
                BENEFICIARY_METHOD);

        return new MonitoringDashboardResponse(
                period.map(entity -> AcademicPeriodResponse.fromEntity(entity, isCurrent(entity)))
                        .orElse(null),
                kpis,
                programsByType(startsOn, endsOn),
                beneficiariesBySector(startsOn, endsOn),
                completionRows(startsOn, endsOn),
                kpis.programsTotal(),
                Instant.now());
    }

    /** The method sentence, so exports can print it without importing the whole payload. */
    public static String beneficiaryMethod() {
        return BENEFICIARY_METHOD;
    }

    private boolean isCurrent(AcademicPeriod period) {
        return academicPeriodService.resolveCurrent()
                .map(current -> current.getId().equals(period.getId()))
                .orElse(false);
    }

    private List<TypeCount> programsByType(LocalDate startsOn, LocalDate endsOn) {
        return (startsOn == null
                ? programRepository.countByProgramType()
                : programRepository.countByProgramTypeForPeriod(startsOn, endsOn)).stream()
                .map(row -> new TypeCount((String) row[0], (String) row[1], asLong(row, 2)))
                .toList();
    }

    /**
     * Folds the {@code [sectorId, sectorName, sex, count]} rows into one entry per sector, biggest
     * first, with the "Not specified" bucket forced last however large it is — it is a data-quality
     * note, not a sector, and sorting it into the middle of the chart would read as one.
     */
    private List<SectorCount> beneficiariesBySector(LocalDate startsOn, LocalDate endsOn) {
        record Tally(String name, long[] counts) {
        }
        Map<String, Tally> bySector = new LinkedHashMap<>();
        for (Object[] row : (startsOn == null
                ? attendanceRepository.countBySectorAndSex()
                : attendanceRepository.countBySectorAndSexForPeriod(startsOn, endsOn))) {
            String sectorId = (String) row[0];
            String name = row[1] == null ? UNSPECIFIED_SECTOR : (String) row[1];
            // A null id is a valid map key here only because LinkedHashMap allows one; that single
            // entry is exactly the unspecified bucket.
            Tally tally = bySector.computeIfAbsent(sectorId, ignored -> new Tally(name, new long[2]));
            long count = asLong(row, 3);
            if (AttendanceRecord.SEX_FEMALE.equals(row[2])) {
                tally.counts()[0] += count;
            } else {
                tally.counts()[1] += count;
            }
        }

        List<SectorCount> sectors = new ArrayList<>();
        bySector.forEach((sectorId, tally) -> sectors.add(new SectorCount(
                sectorId,
                tally.name(),
                tally.counts()[0] + tally.counts()[1],
                tally.counts()[0],
                tally.counts()[1])));

        sectors.sort(Comparator
                .comparing((SectorCount sector) -> sector.sectorId() == null)
                .thenComparing(SectorCount::total, Comparator.reverseOrder())
                .thenComparing(SectorCount::sectorName));
        return List.copyOf(sectors);
    }

    /**
     * The completion table: one page of programs, then two batched rollups keyed by program id.
     * Three queries total regardless of row count.
     */
    private List<CompletionRow> completionRows(LocalDate startsOn, LocalDate endsOn) {
        List<Program> programs = startsOn == null
                ? programRepository.findAllForCompletion(PageRequest.of(0, COMPLETION_ROW_LIMIT))
                : programRepository.findForPeriod(startsOn, endsOn, PageRequest.of(0, COMPLETION_ROW_LIMIT));
        if (programs.isEmpty()) {
            return List.of();
        }

        List<String> ids = programs.stream().map(Program::getId).toList();
        Map<String, long[]> attendance = new HashMap<>();
        for (Object[] row : attendanceRepository.countBySexForPrograms(ids)) {
            long[] counts = attendance.computeIfAbsent((String) row[0], ignored -> new long[2]);
            if (AttendanceRecord.SEX_FEMALE.equals(row[1])) {
                counts[0] += asLong(row, 2);
            } else {
                counts[1] += asLong(row, 2);
            }
        }

        Map<String, boolean[]> evaluations = new HashMap<>();
        for (Object[] row : evaluationRepository.countByTypeForPrograms(ids)) {
            boolean[] present = evaluations.computeIfAbsent((String) row[0], ignored -> new boolean[2]);
            if (asLong(row, 2) > 0) {
                if (Evaluation.TYPE_PRE.equals(row[1])) {
                    present[0] = true;
                } else if (Evaluation.TYPE_POST.equals(row[1])) {
                    present[1] = true;
                }
            }
        }

        return programs.stream().map(program -> {
            long[] counts = attendance.getOrDefault(program.getId(), new long[2]);
            boolean[] present = evaluations.getOrDefault(program.getId(), new boolean[2]);
            return new CompletionRow(
                    program.getId(),
                    program.getTitle(),
                    program.getCommunity() == null ? null : program.getCommunity().getName(),
                    program.getProgramType() == null ? null : program.getProgramType().getName(),
                    program.getStatus(),
                    program.getTargetBeneficiaries(),
                    counts[0] + counts[1],
                    counts[0],
                    counts[1],
                    present[0],
                    present[1]);
        }).toList();
    }

    // --- projection helpers (JPQL aggregates arrive as Object[] of assorted Number subtypes) ---

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.merge((String) row[0], asLong(row, 1), Long::sum);
        }
        return counts;
    }

    private static long asLong(Object[] row, int index) {
        if (row.length <= index || !(row[index] instanceof Number number)) {
            return 0L;
        }
        return number.longValue();
    }
}
