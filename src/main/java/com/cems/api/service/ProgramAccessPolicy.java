package com.cems.api.service;

import com.cems.api.entity.Program;
import com.cems.api.entity.User;
import com.cems.api.exception.ConflictException;
import com.cems.api.repository.ProgramMemberRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.security.Permissions;
import com.cems.api.security.RoleName;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Who may see and act on a given program. Extracted so the proposal phase ({@code ProgramService})
 * and the delivery phase ({@code ProgramActivityService} and friends) share one definition of
 * "owner" and one definition of "restricted to their own" — two copies of that rule would drift,
 * which is the same reason {@code Permissions} exists.
 *
 * <p><strong>The two phases have deliberately opposite status rules, and that is not a bug:</strong>
 * a proposal is editable while {@code draft}/{@code returned} and frozen once it enters the chain;
 * activities and attendance are the reverse — they only exist once the proposal is {@code approved}
 * and stop being editable once the program is {@code completed}. {@link #assertCanDeliver} therefore
 * cannot reuse {@link Program#isEditableByOwner()}.
 */
@Service
public class ProgramAccessPolicy {

    private final Permissions permissions;
    private final UserRepository userRepository;
    private final ProgramMemberRepository memberRepository;

    public ProgramAccessPolicy(Permissions permissions,
            UserRepository userRepository,
            ProgramMemberRepository memberRepository) {
        this.permissions = permissions;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * The signed-in user's id, or null when unauthenticated. The JWT principal is the email, so this
     * costs a lookup — matching how {@code ProgramService} has always resolved it.
     */
    public String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return userRepository.findByEmail(authentication.getName()).map(User::getId).orElse(null);
    }

    /** True when the current user created or leads this program. */
    public boolean isOwner(Program program) {
        String userId = currentUserId();
        return userId != null
                && (userId.equals(program.getCreatedBy()) || userId.equals(program.getFacultyLeadId()));
    }

    public boolean isCoordinatorOrAdmin() {
        return permissions.hasAnyRole(RoleName.ADMIN,
                RoleName.CAMPUS_EXTENSION_COORDINATOR,
                RoleName.EXTENSION_COORDINATOR);
    }

    /** Faculty and student volunteers see only what they created or lead (spec Module 5 §1). */
    public boolean restrictedToOwnPrograms() {
        return permissions.hasAnyRole(RoleName.FACULTY, RoleName.STUDENT_VOLUNTEER)
                && !isCoordinatorOrAdmin()
                && !permissions.hasRole(RoleName.CAMPUS_ADMIN);
    }

    /**
     * True when the current user is assigned to this program (spec Module 5 §1 "own + assigned").
     *
     * <p>Separate from {@link #isOwner} on purpose: assignment confers <em>visibility only</em>. A
     * student volunteer helping run a session must be able to look it up; that is not the same as
     * being able to rewrite the beneficiary record, so {@link #canDeliver} does not consult this.
     */
    public boolean isAssigned(Program program) {
        String userId = currentUserId();
        return userId != null && memberRepository.existsByProgramIdAndUserId(program.getId(), userId);
    }

    public boolean canView(Program program) {
        return !restrictedToOwnPrograms() || isOwner(program) || isAssigned(program);
    }

    /** A program the caller may not see is reported as missing, never as forbidden — no leak. */
    public void assertCanView(Program program) {
        if (!canView(program)) {
            throw new NoSuchElementException("Program not found.");
        }
    }

    /**
     * True when the caller may record delivery against this program — add activities, encode
     * attendance, encode evaluations.
     *
     * <p>Student volunteers are excluded: spec §2.2 has them viewing the programs they help run, not
     * writing the beneficiary record. Everyone else follows the ownership rule.
     */
    public boolean canDeliver(Program program) {
        if (permissions.hasRole(RoleName.STUDENT_VOLUNTEER) && !isCoordinatorOrAdmin()) {
            return false;
        }
        return isOwner(program) || isCoordinatorOrAdmin();
    }

    /** True when the program is at a stage where delivery records may still be written. */
    public boolean isInDeliveryPhase(Program program) {
        return Program.STATUS_APPROVED.equals(program.getStatus())
                || Program.STATUS_ONGOING.equals(program.getStatus());
    }

    /** Quiet form for response flags — "should the client render the add/edit controls?". */
    public boolean canDeliverQuietly(Program program) {
        return canDeliver(program) && isInDeliveryPhase(program);
    }

    /**
     * Gate for every delivery write.
     *
     * <p>The two failure modes are kept distinct exactly as they are in the approval chain: lacking
     * the role or the ownership is {@link AccessDeniedException} (403), while acting on a program
     * that has not been approved yet — or has already finished — is a {@link ConflictException}
     * (409). Collapsing them would tell a faculty lead they lack permission when in truth they are
     * simply early.
     */
    public void assertCanDeliver(Program program) {
        if (!canDeliver(program)) {
            throw new AccessDeniedException(
                    "You may only record activities on programs you created or lead.");
        }
        if (!isInDeliveryPhase(program)) {
            throw new ConflictException(
                    "Activities can only be recorded on an approved or ongoing program; this one is "
                            + program.getStatus().replace('_', ' ') + ".");
        }
    }
}
