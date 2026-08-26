package com.cems.api.service;

import com.cems.api.dto.EvaluationResponse;
import com.cems.api.dto.ProgramActivityRequest;
import com.cems.api.dto.ProgramActivityResponse;
import com.cems.api.dto.SexSplitResponse;
import com.cems.api.entity.AttendanceRecord;
import com.cems.api.entity.Evaluation;
import com.cems.api.entity.Program;
import com.cems.api.entity.ProgramActivity;
import com.cems.api.exception.ConflictException;
import com.cems.api.repository.AttendanceRecordRepository;
import com.cems.api.repository.EvaluationRepository;
import com.cems.api.repository.ProgramActivityRepository;
import com.cems.api.repository.ProgramRepository;
import com.cems.api.service.ProgramStateMachine.Action;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Activities — the sessions an approved program is actually delivered through (spec Module 5b §4).
 *
 * <p>This service owns the two program-status transitions that the delivery phase drives. Both go
 * through {@link ProgramStateMachine}, which remains the only writer of {@code programs.status}:
 *
 * <ul>
 *   <li><b>approved → ongoing</b> the first time an activity is marked {@code done}. Recording
 *       delivery is the event that starts a program; there is no separate "start" button, because a
 *       program with a completed session is running whether or not anyone pressed one.</li>
 *   <li><b>ongoing → completed</b> once every activity is settled ({@code done} or
 *       {@code cancelled}) <em>and</em> at least one post-evaluation exists (AC 5). Evaluated after
 *       every change, so deleting the last unfinished activity completes the program just as
 *       finishing it would.</li>
 * </ul>
 *
 * <p>Both are <b>opportunistic</b>: the state machine is asked to resolve the transition and a
 * {@link ConflictException} is swallowed, because "the program was not in a state to advance" is the
 * normal case here, not an error. That is the one place in this codebase where a 409 is caught
 * rather than propagated, and it is why these are private helpers instead of endpoints.
 */
@Service
@Transactional
public class ProgramActivityService {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            ProgramActivity.STATUS_SCHEDULED,
            ProgramActivity.STATUS_DONE,
            ProgramActivity.STATUS_CANCELLED);

    private final ProgramActivityRepository activityRepository;
    private final ProgramRepository programRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final EvaluationRepository evaluationRepository;
    private final ProgramAccessPolicy accessPolicy;
    private final ProgramStateMachine stateMachine;
    private final ActivityLogService activityLogService;

    public ProgramActivityService(ProgramActivityRepository activityRepository,
            ProgramRepository programRepository,
            AttendanceRecordRepository attendanceRepository,
            EvaluationRepository evaluationRepository,
            ProgramAccessPolicy accessPolicy,
            ProgramStateMachine stateMachine,
            ActivityLogService activityLogService) {
        this.activityRepository = activityRepository;
        this.programRepository = programRepository;
        this.attendanceRepository = attendanceRepository;
        this.evaluationRepository = evaluationRepository;
        this.accessPolicy = accessPolicy;
        this.stateMachine = stateMachine;
        this.activityLogService = activityLogService;
    }

    // --- reads ---

    @Transactional(readOnly = true)
    public List<ProgramActivityResponse> listForProgram(String programId) {
        Program program = findProgram(programId);
        accessPolicy.assertCanView(program);

        List<ProgramActivity> activities =
                activityRepository.findByProgramIdAndDeletedAtIsNullOrderByActivityDateAscStartTimeAsc(programId);
        return toResponses(activities, accessPolicy.canDeliverQuietly(program));
    }

    @Transactional(readOnly = true)
    public ProgramActivityResponse getById(String activityId) {
        ProgramActivity activity = findActive(activityId);
        accessPolicy.assertCanView(activity.getProgram());
        return toResponses(List.of(activity), accessPolicy.canDeliverQuietly(activity.getProgram())).get(0);
    }

    /** The program's running Total/F/M across every non-deleted activity (spec AC 4). */
    @Transactional(readOnly = true)
    public SexSplitResponse attendanceTotalsForProgram(String programId) {
        Program program = findProgram(programId);
        accessPolicy.assertCanView(program);
        return toSplit(attendanceRepository.countBySexForProgram(programId));
    }

    // --- writes ---

    public ProgramActivityResponse create(String programId, ProgramActivityRequest request) {
        Program program = findProgram(programId);
        accessPolicy.assertCanDeliver(program);

        ProgramActivity activity = new ProgramActivity();
        activity.setProgram(program);
        applyRequest(activity, request);
        activity.setCreatedBy(accessPolicy.currentUserId());
        ProgramActivity saved = activityRepository.save(activity);

        activityLogService.record("activity.created", "program_activity", saved.getId(),
                Map.of("programId", programId, "title", saved.getTitle(), "status", saved.getStatus()));

        syncProgramStatus(program);
        return toResponses(List.of(saved), true).get(0);
    }

    public ProgramActivityResponse update(String activityId, ProgramActivityRequest request) {
        ProgramActivity activity = findActive(activityId);
        Program program = activity.getProgram();
        accessPolicy.assertCanDeliver(program);

        applyRequest(activity, request);
        ProgramActivity saved = activityRepository.save(activity);

        activityLogService.record("activity.updated", "program_activity", saved.getId(),
                Map.of("programId", program.getId(), "title", saved.getTitle(), "status", saved.getStatus()));

        syncProgramStatus(program);
        return toResponses(List.of(saved), true).get(0);
    }

    /**
     * Soft-deletes an activity. Its attendance and evaluation rows are left in place — they are
     * personal-data records and the audit trail the accomplishment report is built from; every query
     * in this module already filters on {@code programActivity.deletedAt IS NULL}, so they stop
     * counting without being destroyed.
     */
    public void delete(String activityId) {
        ProgramActivity activity = findActive(activityId);
        Program program = activity.getProgram();
        accessPolicy.assertCanDeliver(program);

        activity.setDeletedAt(Instant.now());
        activityRepository.save(activity);

        activityLogService.record("activity.deleted", "program_activity", activity.getId(),
                Map.of("programId", program.getId(), "title", activity.getTitle()));

        syncProgramStatus(program);
    }

    // --- program status side effects ---

    /**
     * Re-evaluates the program's status after any activity change. Called on every write so the
     * program never sits in a stale state — deleting the last scheduled activity should complete a
     * program exactly as marking it done would.
     */
    void syncProgramStatus(Program program) {
        advanceToOngoingIfDelivered(program);
        advanceToCompletedIfEligible(program);
    }

    /** The first {@code done} activity starts the program. */
    private void advanceToOngoingIfDelivered(Program program) {
        if (!Program.STATUS_APPROVED.equals(program.getStatus())) {
            return;
        }
        long done = activityRepository.countByProgramIdAndDeletedAtIsNullAndStatus(
                program.getId(), ProgramActivity.STATUS_DONE);
        if (done > 0) {
            tryTransition(program, Action.START, "program.started");
        }
    }

    /**
     * Completion needs three things at once (spec AC 5): at least one activity, every activity
     * settled, and at least one post-evaluation. The "at least one activity" guard matters — without
     * it a program with no activities would satisfy "all settled" vacuously and complete itself.
     */
    private void advanceToCompletedIfEligible(Program program) {
        if (!Program.STATUS_ONGOING.equals(program.getStatus())) {
            return;
        }
        if (activityRepository.countByProgramIdAndDeletedAtIsNull(program.getId()) == 0) {
            return;
        }
        if (activityRepository.countUnsettledByProgramId(program.getId()) > 0) {
            return;
        }
        if (evaluationRepository.countPostEvaluationsForProgram(program.getId()) == 0) {
            return;
        }
        tryTransition(program, Action.COMPLETE, "program.completed");
    }

    /**
     * Attempts a transition, treating an illegal one as "not yet" rather than an error. See the
     * class javadoc for why this is the one place a {@link ConflictException} is swallowed.
     */
    private void tryTransition(Program program, Action action, String logAction) {
        try {
            stateMachine.apply(program, stateMachine.resolve(program, action, null, null));
        } catch (ConflictException notReady) {
            return;
        }
        programRepository.save(program);
        activityLogService.record(logAction, "program", program.getId(),
                Map.of("status", program.getStatus()));
    }

    // --- mapping ---

    private List<ProgramActivityResponse> toResponses(List<ProgramActivity> activities, boolean canEdit) {
        if (activities.isEmpty()) {
            return List.of();
        }
        List<String> ids = activities.stream().map(ProgramActivity::getId).toList();

        // One grouped query for the whole tab, and one for its evaluations — never per row.
        Map<String, SexSplitResponse> attendance = splitsByActivity(
                attendanceRepository.countBySexForActivities(ids));
        Map<String, List<EvaluationResponse>> evaluations = new HashMap<>();
        for (Evaluation evaluation : evaluationRepository.findByProgramActivityIdInOrderByCreatedAtAsc(ids)) {
            evaluations.computeIfAbsent(evaluation.getProgramActivity().getId(), key -> new ArrayList<>())
                    .add(EvaluationResponse.fromEntity(evaluation));
        }

        return activities.stream()
                .map(activity -> ProgramActivityResponse.fromEntity(
                        activity,
                        attendance.getOrDefault(activity.getId(), SexSplitResponse.EMPTY),
                        evaluations.getOrDefault(activity.getId(), List.of()),
                        canEdit))
                .toList();
    }

    /** Rows of {@code [activityId, sex, count]} → one split per activity. */
    private Map<String, SexSplitResponse> splitsByActivity(List<Object[]> rows) {
        Map<String, long[]> counts = new HashMap<>();
        for (Object[] row : rows) {
            long[] split = counts.computeIfAbsent((String) row[0], key -> new long[2]);
            long count = (Long) row[2];
            if (AttendanceRecord.SEX_FEMALE.equals(row[1])) {
                split[0] += count;
            } else {
                split[1] += count;
            }
        }
        Map<String, SexSplitResponse> result = new HashMap<>();
        counts.forEach((activityId, split) ->
                result.put(activityId, new SexSplitResponse(split[0] + split[1], split[0], split[1])));
        return result;
    }

    /** Rows of {@code [sex, count]} → one split. */
    static SexSplitResponse toSplit(List<Object[]> rows) {
        long female = 0;
        long male = 0;
        for (Object[] row : rows) {
            long count = (Long) row[1];
            if (AttendanceRecord.SEX_FEMALE.equals(row[0])) {
                female += count;
            } else {
                male += count;
            }
        }
        return new SexSplitResponse(female + male, female, male);
    }

    private void applyRequest(ProgramActivity activity, ProgramActivityRequest request) {
        activity.setTitle(request.title().trim());
        activity.setActivityDate(request.activityDate());
        activity.setStartTime(request.startTime());
        activity.setEndTime(request.endTime());
        activity.setVenue(blankToNull(request.venue()));
        activity.setNotes(blankToNull(request.notes()));

        if (request.endTime() != null && request.startTime() != null
                && request.endTime().isBefore(request.startTime())) {
            throw new IllegalArgumentException("The end time cannot be before the start time.");
        }
        if (request.status() != null && !request.status().isBlank()) {
            String status = request.status().trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_STATUSES.contains(status)) {
                throw new IllegalArgumentException(
                        "Activity status must be one of: " + String.join(", ", ALLOWED_STATUSES) + ".");
            }
            activity.setStatus(status);
        }
    }

    // --- lookups ---

    /** Package-private so the attendance and evaluation services resolve activities the same way. */
    ProgramActivity findActive(String activityId) {
        return activityRepository.findByIdAndDeletedAtIsNull(activityId)
                .orElseThrow(() -> new NoSuchElementException("Activity not found."));
    }

    private Program findProgram(String programId) {
        return programRepository.findByIdAndDeletedAtIsNull(programId)
                .orElseThrow(() -> new NoSuchElementException("Program not found."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
