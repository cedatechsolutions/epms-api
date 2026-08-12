package com.cems.api.service;

import com.cems.api.entity.Program;
import com.cems.api.entity.ProgramApproval;
import com.cems.api.exception.ConflictException;
import com.cems.api.security.RoleName;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single authority on how a proposal moves through the four-stage CvSU approval chain
 * (spec Module 5). Nothing else in the codebase may assign {@code Program.status}.
 *
 * <p>The chain, read as "each stage's action produces the next status":
 *
 * <pre>
 *   Stage 1  draft | returned  --submit-----&gt;  submitted              faculty (owner)
 *   Stage 2  submitted         --note-------&gt;  coordinator_review     extension_coordinator
 *   Stage 3  coordinator_review--recommend--&gt;  recommending_approval  campus_extension_coordinator
 *   Stage 4  recommending_approval--approve-&gt;  approved               campus_admin
 *
 *   Any of stages 2-4  --return--&gt;  returned   (comment REQUIRED)
 *   returned  --submit--&gt;  submitted           (re-enters at stage 2, per spec)
 * </pre>
 *
 * <p><strong>Two failure modes that look alike but are not:</strong> acting on a proposal in the
 * wrong status is a {@link ConflictException} (409) — the request was legal for the actor but the
 * proposal has moved on; acting without the stage's role is an
 * {@link org.springframework.security.access.AccessDeniedException} (403). Callers and tests must
 * keep them distinct.
 *
 * <p><strong>Deliberate asymmetry:</strong> {@code admin} is NOT permitted to act on stages 2-4.
 * The spec permission matrix (§2.2) marks admin "—" on every review row because the approval chain
 * mirrors real signatories; a system administrator standing in for a campus administrator would
 * make {@code program_approvals} a record of something that never happened. This is intentional
 * and differs from every other policy in {@code Permissions}.
 *
 * <p>{@code approved → ongoing → completed} are declared here but have no Phase 4 trigger; Phase 5
 * drives them from activities and evaluations.
 */
@Service
public class ProgramStateMachine {

    /** A stage action requested by a client. */
    public enum Action {
        SUBMIT, NOTE, RECOMMEND, APPROVE, RETURN, CANCEL, START, COMPLETE
    }

    /**
     * One row of the transition table.
     *
     * @param from         statuses the action may be applied to
     * @param to           status produced on success
     * @param stage        approval-chain stage number recorded on the audit row (0 = not a chain action)
     * @param requiredRole role permitted to perform it, or null when ownership decides instead
     * @param auditAction  {@code program_approvals.action} written, or null when the action is not audited
     */
    private record Transition(Set<String> from,
                              String to,
                              int stage,
                              RoleName requiredRole,
                              String auditAction) {
    }

    /**
     * The complete legal transition set. Adding a stage means adding a row here and nowhere else —
     * that is the property {@code ProgramStateMachineTest} pins.
     */
    private static final Map<Action, Transition> TRANSITIONS = buildTransitions();

    private static Map<Action, Transition> buildTransitions() {
        Map<Action, Transition> transitions = new LinkedHashMap<>();

        // Stage 1 — the owning faculty submits. Role is not checked here: ownership is, by the caller,
        // because coordinators may also submit on a proposal they created themselves.
        transitions.put(Action.SUBMIT, new Transition(
                Set.of(Program.STATUS_DRAFT, Program.STATUS_RETURNED),
                Program.STATUS_SUBMITTED, 1, null, ProgramApproval.ACTION_SUBMITTED));

        // Stage 2 — extension coordinator notes it onward.
        transitions.put(Action.NOTE, new Transition(
                Set.of(Program.STATUS_SUBMITTED),
                Program.STATUS_COORDINATOR_REVIEW, 2,
                RoleName.EXTENSION_COORDINATOR, ProgramApproval.ACTION_NOTED));

        // Stage 3 — campus extension coordinator recommends it for approval.
        transitions.put(Action.RECOMMEND, new Transition(
                Set.of(Program.STATUS_COORDINATOR_REVIEW),
                Program.STATUS_RECOMMENDING_APPROVAL, 3,
                RoleName.CAMPUS_EXTENSION_COORDINATOR, ProgramApproval.ACTION_RECOMMENDED));

        // Stage 4 — campus administrator gives final approval.
        transitions.put(Action.APPROVE, new Transition(
                Set.of(Program.STATUS_RECOMMENDING_APPROVAL),
                Program.STATUS_APPROVED, 4,
                RoleName.CAMPUS_ADMIN, ProgramApproval.ACTION_APPROVED));

        // Phase 5 drives these two; declared now so the machine is complete and testable.
        transitions.put(Action.START, new Transition(
                Set.of(Program.STATUS_APPROVED),
                Program.STATUS_ONGOING, 0, null, null));
        transitions.put(Action.COMPLETE, new Transition(
                Set.of(Program.STATUS_ONGOING),
                Program.STATUS_COMPLETED, 0, null, null));

        // Cancellation is reachable from any non-terminal status.
        transitions.put(Action.CANCEL, new Transition(
                Set.of(Program.STATUS_DRAFT, Program.STATUS_SUBMITTED, Program.STATUS_COORDINATOR_REVIEW,
                        Program.STATUS_RECOMMENDING_APPROVAL, Program.STATUS_APPROVED,
                        Program.STATUS_RETURNED, Program.STATUS_ONGOING),
                Program.STATUS_CANCELLED, 0, null, null));

        return Map.copyOf(transitions);
    }

    /** Stages a return may be issued from, mapped to the role that owns that stage. */
    private static final Map<String, RoleName> RETURNABLE_STAGES = Map.of(
            Program.STATUS_SUBMITTED, RoleName.EXTENSION_COORDINATOR,
            Program.STATUS_COORDINATOR_REVIEW, RoleName.CAMPUS_EXTENSION_COORDINATOR,
            Program.STATUS_RECOMMENDING_APPROVAL, RoleName.CAMPUS_ADMIN);

    /** Stage number for each returnable status, for the audit row. */
    private static final Map<String, Integer> RETURN_STAGE_NUMBERS = Map.of(
            Program.STATUS_SUBMITTED, 2,
            Program.STATUS_COORDINATOR_REVIEW, 3,
            Program.STATUS_RECOMMENDING_APPROVAL, 4);

    /**
     * Result of a legal transition: what the caller should persist and audit.
     *
     * @param toStatus    the program's new status
     * @param stage       stage number for the audit row
     * @param stageRole   role code of the acting signatory
     * @param auditAction {@code program_approvals.action}, or null when this transition is not audited
     */
    public record TransitionResult(String toStatus, int stage, String stageRole, String auditAction) {
    }

    /**
     * Validates a stage action and returns what it produces, without mutating anything.
     *
     * @param program   the proposal being acted on
     * @param action    the requested action
     * @param actorRole the acting user's role, used for the stage-role check and the audit row
     * @param comment   free-text comment; required for {@link Action#RETURN}
     * @throws ConflictException        (409) when the action is illegal from the current status
     * @throws IllegalArgumentException (422) when a return carries no comment
     */
    public TransitionResult resolve(Program program, Action action, RoleName actorRole, String comment) {
        if (action == Action.RETURN) {
            return resolveReturn(program, actorRole, comment);
        }

        Transition transition = TRANSITIONS.get(action);
        if (transition == null) {
            throw new IllegalArgumentException("Unsupported program action: " + action);
        }
        if (!transition.from().contains(program.getStatus())) {
            throw new ConflictException(illegalTransitionMessage(program, action, transition.from()));
        }
        return new TransitionResult(
                transition.to(),
                transition.stage(),
                actorRole == null ? null : actorRole.code(),
                transition.auditAction());
    }

    /** A return is legal from any of the three review stages and always requires a reason. */
    private TransitionResult resolveReturn(Program program, RoleName actorRole, String comment) {
        Integer stage = RETURN_STAGE_NUMBERS.get(program.getStatus());
        if (stage == null) {
            throw new ConflictException("A proposal can only be returned while it is under review; this one is "
                    + humanize(program.getStatus()) + ".");
        }
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("A comment is required when returning a proposal.");
        }
        return new TransitionResult(
                Program.STATUS_RETURNED,
                stage,
                actorRole == null ? null : actorRole.code(),
                ProgramApproval.ACTION_RETURNED);
    }

    /**
     * The role that owns the stage a proposal is currently sitting at, or null when it is not in the
     * approval chain. Drives both the 403 check and the frontend's "which buttons do I render?".
     */
    public RoleName stageOwner(Program program) {
        return RETURNABLE_STAGES.get(program.getStatus());
    }

    /**
     * True when {@code role} may act on the stage this proposal currently sits at. Note that admin
     * is excluded by design — see the class javadoc.
     */
    public boolean canActOnCurrentStage(Program program, RoleName role) {
        RoleName owner = stageOwner(program);
        return owner != null && owner == role;
    }

    /** Applies a resolved transition. The only place {@code Program.status} is assigned. */
    public void apply(Program program, TransitionResult result) {
        program.setStatus(result.toStatus());
    }

    /** Actions that are legal from the given status — used by tests and by the detail response. */
    public List<Action> legalActionsFrom(String status) {
        List<Action> legal = new java.util.ArrayList<>(TRANSITIONS.entrySet().stream()
                .filter(entry -> entry.getValue().from().contains(status))
                .map(Map.Entry::getKey)
                .toList());
        if (RETURN_STAGE_NUMBERS.containsKey(status)) {
            legal.add(Action.RETURN);
        }
        return List.copyOf(legal);
    }

    private String illegalTransitionMessage(Program program, Action action, Set<String> allowedFrom) {
        String expected = allowedFrom.stream()
                .map(ProgramStateMachine::humanize)
                .sorted()
                .reduce((a, b) -> a + " or " + b)
                .orElse("another status");
        return "Cannot " + action.name().toLowerCase(Locale.ROOT) + " a proposal that is "
                + humanize(program.getStatus()) + "; it must be " + expected + ".";
    }

    /** "coordinator_review" → "under coordinator review", for readable 409 messages. */
    private static String humanize(String status) {
        if (status == null) {
            return "in an unknown state";
        }
        return switch (status) {
            case Program.STATUS_COORDINATOR_REVIEW -> "under coordinator review";
            case Program.STATUS_RECOMMENDING_APPROVAL -> "awaiting final approval";
            default -> String.join(" ", Arrays.stream(status.split("_")).toList());
        };
    }
}
