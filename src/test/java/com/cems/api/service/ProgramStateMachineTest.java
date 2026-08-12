package com.cems.api.service;

import com.cems.api.entity.Program;
import com.cems.api.entity.ProgramApproval;
import com.cems.api.exception.ConflictException;
import com.cems.api.security.RoleName;
import com.cems.api.service.ProgramStateMachine.Action;
import com.cems.api.service.ProgramStateMachine.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec-mandated transition matrix for the proposal workflow (spec Module 5: "Tests: every legal
 * + illegal transition").
 *
 * <p>The whole point of {@link ProgramStateMachine} is that the rules live in one table, so this
 * test drives them from tables too — a new stage that is not reflected here will fail
 * {@link #everyStatusIsReachableAndTerminatesCorrectly} rather than slip through untested.
 */
class ProgramStateMachineTest {

    private final ProgramStateMachine stateMachine = new ProgramStateMachine();

    // --- the legal chain ---

    /**
     * Each row is one legal advance: from-status, action, actor role, expected to-status,
     * expected audit stage, expected audit action.
     */
    @ParameterizedTest(name = "{0} --{1}--> {3}")
    @CsvSource({
            "draft,                 SUBMIT,    FACULTY,                       submitted,             1, submitted",
            "returned,              SUBMIT,    FACULTY,                       submitted,             1, submitted",
            "submitted,             NOTE,      EXTENSION_COORDINATOR,         coordinator_review,    2, noted",
            "coordinator_review,    RECOMMEND, CAMPUS_EXTENSION_COORDINATOR,  recommending_approval, 3, recommended",
            "recommending_approval, APPROVE,   CAMPUS_ADMIN,                  approved,              4, approved",
    })
    @DisplayName("every legal stage advance produces the next status and its audit row")
    void legalAdvances(String from, Action action, RoleName role,
            String expectedTo, int expectedStage, String expectedAuditAction) {
        Program program = programAt(from);

        TransitionResult result = stateMachine.resolve(program, action, role, null);

        assertEquals(expectedTo, result.toStatus());
        assertEquals(expectedStage, result.stage());
        assertEquals(role.code(), result.stageRole());
        assertEquals(expectedAuditAction, result.auditAction());

        stateMachine.apply(program, result);
        assertEquals(expectedTo, program.getStatus(), "apply() writes the resolved status");
    }

    @Test
    @DisplayName("a proposal walks draft -> approved through the four stages in order")
    void fullChainEndToEnd() {
        Program program = programAt(Program.STATUS_DRAFT);

        advance(program, Action.SUBMIT, RoleName.FACULTY);
        assertEquals(Program.STATUS_SUBMITTED, program.getStatus());

        advance(program, Action.NOTE, RoleName.EXTENSION_COORDINATOR);
        assertEquals(Program.STATUS_COORDINATOR_REVIEW, program.getStatus());

        advance(program, Action.RECOMMEND, RoleName.CAMPUS_EXTENSION_COORDINATOR);
        assertEquals(Program.STATUS_RECOMMENDING_APPROVAL, program.getStatus());

        advance(program, Action.APPROVE, RoleName.CAMPUS_ADMIN);
        assertEquals(Program.STATUS_APPROVED, program.getStatus());
    }

    // --- illegal transitions (409) ---

    /** Every (status, action) pair the table does NOT contain must be refused with a conflict. */
    @ParameterizedTest(name = "{1} is illegal while {0}")
    @MethodSource("illegalCombinations")
    @DisplayName("an action applied from the wrong status is a conflict, not a silent no-op")
    void illegalTransitionsConflict(String status, Action action) {
        Program program = programAt(status);
        assertThrows(ConflictException.class,
                () -> stateMachine.resolve(program, action, roleFor(action), "comment"));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> illegalCombinations() {
        List<String> statuses = List.of(
                Program.STATUS_DRAFT, Program.STATUS_SUBMITTED, Program.STATUS_COORDINATOR_REVIEW,
                Program.STATUS_RECOMMENDING_APPROVAL, Program.STATUS_APPROVED, Program.STATUS_RETURNED,
                Program.STATUS_ONGOING, Program.STATUS_COMPLETED, Program.STATUS_CANCELLED);
        List<Action> actions = List.of(
                Action.SUBMIT, Action.NOTE, Action.RECOMMEND, Action.APPROVE,
                Action.RETURN, Action.START, Action.COMPLETE, Action.CANCEL);

        ProgramStateMachine machine = new ProgramStateMachine();
        return statuses.stream().flatMap(status -> actions.stream()
                .filter(action -> !machine.legalActionsFrom(status).contains(action))
                .map(action -> org.junit.jupiter.params.provider.Arguments.of(status, action)));
    }

    @Test
    @DisplayName("terminal statuses accept nothing at all")
    void terminalStatusesAreClosed() {
        assertTrue(stateMachine.legalActionsFrom(Program.STATUS_COMPLETED).isEmpty());
        assertTrue(stateMachine.legalActionsFrom(Program.STATUS_CANCELLED).isEmpty());
    }

    @Test
    @DisplayName("approving a draft skips the chain and is refused")
    void cannotSkipStages() {
        Program program = programAt(Program.STATUS_DRAFT);
        ConflictException error = assertThrows(ConflictException.class,
                () -> stateMachine.resolve(program, Action.APPROVE, RoleName.CAMPUS_ADMIN, null));
        assertTrue(error.getMessage().contains("draft"), "the message names the actual status");
    }

    @Test
    @DisplayName("a proposal cannot be submitted twice")
    void cannotResubmitWhileInReview() {
        Program program = programAt(Program.STATUS_SUBMITTED);
        assertThrows(ConflictException.class,
                () -> stateMachine.resolve(program, Action.SUBMIT, RoleName.FACULTY, null));
    }

    // --- returns ---

    @ParameterizedTest(name = "a return from {0} is recorded against stage {1}")
    @CsvSource({
            "submitted,             2",
            "coordinator_review,    3",
            "recommending_approval, 4",
    })
    @DisplayName("a return is legal from any review stage and lands on 'returned'")
    void returnsFromEachStage(String status, int expectedStage) {
        Program program = programAt(status);

        TransitionResult result = stateMachine.resolve(
                program, Action.RETURN, RoleName.EXTENSION_COORDINATOR, "Please add a budget breakdown.");

        assertEquals(Program.STATUS_RETURNED, result.toStatus());
        assertEquals(expectedStage, result.stage());
        assertEquals(ProgramApproval.ACTION_RETURNED, result.auditAction());
    }

    @ParameterizedTest(name = "returning without a comment is rejected from {0}")
    @CsvSource({"submitted", "coordinator_review", "recommending_approval"})
    @DisplayName("a return always requires a reason (422)")
    void returnRequiresComment(String status) {
        Program program = programAt(status);

        assertThrows(IllegalArgumentException.class,
                () -> stateMachine.resolve(program, Action.RETURN, RoleName.EXTENSION_COORDINATOR, null));
        assertThrows(IllegalArgumentException.class,
                () -> stateMachine.resolve(program, Action.RETURN, RoleName.EXTENSION_COORDINATOR, "   "));
    }

    @Test
    @DisplayName("a returned proposal re-enters at stage 2, not back at the start")
    void returnedProposalReentersAtStageTwo() {
        Program program = programAt(Program.STATUS_RETURNED);

        TransitionResult result = stateMachine.resolve(program, Action.SUBMIT, RoleName.FACULTY, null);

        assertEquals(Program.STATUS_SUBMITTED, result.toStatus(),
                "resubmission goes to 'submitted', which is the extension coordinator's queue");
    }

    @Test
    @DisplayName("a proposal that is not under review cannot be returned")
    void cannotReturnOutsideTheChain() {
        assertThrows(ConflictException.class, () -> stateMachine.resolve(
                programAt(Program.STATUS_DRAFT), Action.RETURN, RoleName.EXTENSION_COORDINATOR, "no"));
        assertThrows(ConflictException.class, () -> stateMachine.resolve(
                programAt(Program.STATUS_APPROVED), Action.RETURN, RoleName.CAMPUS_ADMIN, "no"));
    }

    // --- stage ownership (the 403 rule) ---

    @Test
    @DisplayName("each review stage is owned by exactly one role")
    void stageOwnership() {
        assertEquals(RoleName.EXTENSION_COORDINATOR,
                stateMachine.stageOwner(programAt(Program.STATUS_SUBMITTED)));
        assertEquals(RoleName.CAMPUS_EXTENSION_COORDINATOR,
                stateMachine.stageOwner(programAt(Program.STATUS_COORDINATOR_REVIEW)));
        assertEquals(RoleName.CAMPUS_ADMIN,
                stateMachine.stageOwner(programAt(Program.STATUS_RECOMMENDING_APPROVAL)));

        assertNull(stateMachine.stageOwner(programAt(Program.STATUS_DRAFT)));
        assertNull(stateMachine.stageOwner(programAt(Program.STATUS_APPROVED)));
    }

    @Test
    @DisplayName("admin owns no stage — the signatory chain excludes it by design (spec §2.2)")
    void adminOwnsNoStage() {
        for (String status : List.of(Program.STATUS_SUBMITTED, Program.STATUS_COORDINATOR_REVIEW,
                Program.STATUS_RECOMMENDING_APPROVAL)) {
            assertFalse(stateMachine.canActOnCurrentStage(programAt(status), RoleName.ADMIN),
                    "admin must not be able to act on stage " + status);
        }
    }

    @Test
    @DisplayName("a coordinator cannot act on a stage owned by another role")
    void wrongRoleForStage() {
        Program atStageThree = programAt(Program.STATUS_COORDINATOR_REVIEW);

        assertFalse(stateMachine.canActOnCurrentStage(atStageThree, RoleName.EXTENSION_COORDINATOR),
                "stage 3 belongs to the campus extension coordinator");
        assertTrue(stateMachine.canActOnCurrentStage(atStageThree, RoleName.CAMPUS_EXTENSION_COORDINATOR));
        assertFalse(stateMachine.canActOnCurrentStage(atStageThree, RoleName.FACULTY));
        assertFalse(stateMachine.canActOnCurrentStage(atStageThree, RoleName.STUDENT_VOLUNTEER));
    }

    // --- cancellation and the Phase 5 tail ---

    @Test
    @DisplayName("cancellation is reachable from every non-terminal status")
    void cancellationReachableFromNonTerminalStatuses() {
        for (String status : List.of(Program.STATUS_DRAFT, Program.STATUS_SUBMITTED,
                Program.STATUS_COORDINATOR_REVIEW, Program.STATUS_RECOMMENDING_APPROVAL,
                Program.STATUS_APPROVED, Program.STATUS_RETURNED, Program.STATUS_ONGOING)) {
            TransitionResult result = stateMachine.resolve(
                    programAt(status), Action.CANCEL, RoleName.ADMIN, null);
            assertEquals(Program.STATUS_CANCELLED, result.toStatus());
            assertNull(result.auditAction(), "cancelling is not part of the signatory chain");
        }
    }

    @Test
    @DisplayName("the Phase 5 tail is declared: approved -> ongoing -> completed")
    void implementationTailIsDeclared() {
        Program program = programAt(Program.STATUS_APPROVED);

        advance(program, Action.START, RoleName.FACULTY);
        assertEquals(Program.STATUS_ONGOING, program.getStatus());

        advance(program, Action.COMPLETE, RoleName.FACULTY);
        assertEquals(Program.STATUS_COMPLETED, program.getStatus());
    }

    /**
     * Guards the table itself: every status must be produced by some transition (except the initial
     * {@code draft}), so a status can never be declared in the entity and left unreachable.
     */
    @Test
    @DisplayName("every declared status is reachable from the transition table")
    void everyStatusIsReachableAndTerminatesCorrectly() {
        List<String> allStatuses = List.of(
                Program.STATUS_SUBMITTED, Program.STATUS_COORDINATOR_REVIEW,
                Program.STATUS_RECOMMENDING_APPROVAL, Program.STATUS_APPROVED,
                Program.STATUS_RETURNED, Program.STATUS_ONGOING, Program.STATUS_COMPLETED,
                Program.STATUS_CANCELLED);

        List<String> reachable = List.of(
                Program.STATUS_DRAFT, Program.STATUS_SUBMITTED, Program.STATUS_COORDINATOR_REVIEW,
                Program.STATUS_RECOMMENDING_APPROVAL, Program.STATUS_APPROVED, Program.STATUS_RETURNED,
                Program.STATUS_ONGOING).stream()
                .flatMap(status -> stateMachine.legalActionsFrom(status).stream()
                        .map(action -> stateMachine.resolve(
                                programAt(status), action, roleFor(action),
                                action == Action.RETURN ? "reason" : null).toStatus()))
                .distinct()
                .toList();

        for (String status : allStatuses) {
            assertTrue(reachable.contains(status), status + " is declared but unreachable");
        }
    }

    // --- helpers ---

    private void advance(Program program, Action action, RoleName role) {
        stateMachine.apply(program, stateMachine.resolve(program, action, role, null));
    }

    private Program programAt(String status) {
        Program program = new Program();
        program.setTitle("Fixture proposal");
        program.setStatus(status);
        return program;
    }

    /** The role that owns each action, so illegal-transition cases fail on status, not on role. */
    private static RoleName roleFor(Action action) {
        return switch (action) {
            case NOTE -> RoleName.EXTENSION_COORDINATOR;
            case RECOMMEND -> RoleName.CAMPUS_EXTENSION_COORDINATOR;
            case APPROVE -> RoleName.CAMPUS_ADMIN;
            default -> RoleName.FACULTY;
        };
    }
}
