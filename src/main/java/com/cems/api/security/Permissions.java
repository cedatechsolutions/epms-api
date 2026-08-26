package com.cems.api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Central authorization policy mirroring the spec permission matrix (§2.2). Controllers
 * reference these methods from {@code @PreAuthorize("@permissions.<action>()")} rather than
 * scattering role-string checks. As later modules land, add one method per matrix row here
 * so authorization stays in a single, testable place.
 */
@Component("permissions")
public class Permissions {

    // --- Module 1: user & access management ---

    /** Only the system administrator manages users and roles. */
    public boolean canManageUsers() {
        return hasRole(RoleName.ADMIN);
    }

    /** Only the system administrator reads the audit trail. */
    public boolean canViewActivityLogs() {
        return hasRole(RoleName.ADMIN);
    }

    /**
     * Read the name-and-role directory that fills people pickers — currently the proposal form's
     * faculty-lead select.
     *
     * <p>Intentionally wider than {@link #canManageUsers()} and matched to
     * {@link #canCreatePrograms()}: a coordinator drafting a proposal on a colleague's behalf has to
     * be able to name them, and they are not administrators. The exposure is bounded by the DTO, not
     * by this check — {@code UserOptionResponse} carries name, email and roles and nothing else,
     * while full user records stay behind {@code canManageUsers()}.
     */
    public boolean canBrowseUserDirectory() {
        return canCreatePrograms();
    }

    // --- Module 2: community profiling (spec §2.2) ---

    /** Any authenticated user may view communities and sectors (matrix: view for all roles). */
    public boolean canViewCommunities() {
        return isAuthenticated();
    }

    /** Only admin and the two extension-coordinator roles may create/update/delete communities. */
    public boolean canManageCommunities() {
        return hasAnyRole(RoleName.ADMIN,
                RoleName.CAMPUS_EXTENSION_COORDINATOR,
                RoleName.EXTENSION_COORDINATOR);
    }

    // --- Module 3: needs assessment (spec §2.2) ---

    /**
     * Coarse gate for creating/managing surveys: admin, both coordinator roles, and faculty
     * (spec §2.2 "Create/deploy surveys"). Faculty are further restricted to their own surveys
     * by an ownership check in the service layer.
     */
    public boolean canCreateSurveys() {
        return hasAnyRole(RoleName.ADMIN,
                RoleName.CAMPUS_EXTENSION_COORDINATOR,
                RoleName.EXTENSION_COORDINATOR,
                RoleName.FACULTY);
    }

    /** View surveys/assessment results: every role except the student volunteer (spec §2.2). */
    public boolean canViewAssessments() {
        return isAuthenticated() && !hasRole(RoleName.STUDENT_VOLUNTEER);
    }

    // --- Module 4: recommendation engine (spec §2.2) ---

    /**
     * Configure the program-type library and scoring matrix: admin and the campus extension
     * coordinator only (matrix row "Configure scoring matrix"). Note this is narrower than
     * {@link #canCreateSurveys()} — faculty and the plain extension coordinator are excluded.
     */
    public boolean canConfigureScoringMatrix() {
        return hasAnyRole(RoleName.ADMIN, RoleName.CAMPUS_EXTENSION_COORDINATOR);
    }

    /**
     * Generate recommendations and rule on them (matrix row "Accept/reject recommendations"):
     * admin and both extension-coordinator roles. Faculty may view but not decide.
     */
    public boolean canDecideRecommendations() {
        return hasAnyRole(RoleName.ADMIN,
                RoleName.CAMPUS_EXTENSION_COORDINATOR,
                RoleName.EXTENSION_COORDINATOR);
    }

    // --- Module 5a: programs & approval workflow (spec §2.2) ---

    /**
     * Create and edit proposals: admin, both coordinator roles, and faculty (matrix row "Create
     * proposals"). Faculty are further restricted to proposals they created or lead by an ownership
     * check in {@code ProgramService}. Campus admin is excluded — they approve, they do not author.
     */
    public boolean canCreatePrograms() {
        return hasAnyRole(RoleName.ADMIN,
                RoleName.CAMPUS_EXTENSION_COORDINATOR,
                RoleName.EXTENSION_COORDINATOR,
                RoleName.FACULTY);
    }

    /**
     * View proposals: every authenticated role, including student volunteers, who need to see the
     * programs they help run. The list is narrowed per-role in the service layer.
     */
    public boolean canViewPrograms() {
        return isAuthenticated();
    }

    /**
     * Stage 2 — "Note" a submitted proposal onward.
     *
     * <p><strong>Read this before "fixing" it:</strong> the next three checks each name exactly one
     * role and deliberately EXCLUDE admin, unlike every other policy in this class. Spec §2.2 marks
     * admin "—" on all three review rows because the chain mirrors real CvSU signatories — letting a
     * system administrator note, recommend or approve would make {@code program_approvals} a record
     * of a signature that never happened. Widening these is a spec change, not a bug fix.
     */
    public boolean canReviewProposals() {
        return hasRole(RoleName.EXTENSION_COORDINATOR);
    }

    /** Stage 3 — "Recommend" a noted proposal for final approval. Campus extension coordinator only. */
    public boolean canRecommendApproval() {
        return hasRole(RoleName.CAMPUS_EXTENSION_COORDINATOR);
    }

    /** Stage 4 — final approval. Campus administrator only. */
    public boolean canFinalApprove() {
        return hasRole(RoleName.CAMPUS_ADMIN);
    }

    /**
     * Coarse gate for the three review endpoints: the caller holds at least one stage role. The
     * endpoint-specific check above still runs, and the state machine independently verifies the
     * role owns the stage the proposal is actually sitting at.
     */
    public boolean canActOnApprovalChain() {
        return hasAnyRole(RoleName.EXTENSION_COORDINATOR,
                RoleName.CAMPUS_EXTENSION_COORDINATOR,
                RoleName.CAMPUS_ADMIN);
    }

    // --- Module 5b: activities, attendance, evaluations (spec §2.2) ---

    /**
     * Record delivery — add activities, encode attendance, encode evaluations.
     *
     * <p>Same role set as {@link #canCreatePrograms()}, and narrowed further per-program by
     * {@code ProgramAccessPolicy}: the caller must also own the program (created it or leads it) and
     * the program must be approved or ongoing. Student volunteers are excluded here — spec §2.2 has
     * them viewing the programs they help run, not writing the beneficiary record.
     */
    public boolean canRecordDelivery() {
        return canCreatePrograms();
    }

    /**
     * See beneficiary <em>names</em>, as opposed to the counts everyone with program access gets.
     *
     * <p><strong>This answers open question #1 in IMPLEMENTATION_PLAN.md §4 with its documented
     * default: student volunteers see masked counts, not names.</strong> Attendance is personal data
     * under RA 10173 and a volunteer needs the totals to run a session, not the roster — so the
     * safer reading is the default until the Extension Services Center rules otherwise. Flipping it
     * is a one-line change here plus a line in the plan's open-questions table; nothing else in the
     * codebase re-derives this rule, because {@code AttendanceService} nulls the name server-side
     * rather than letting clients hide a value they were still sent.
     */
    public boolean canViewBeneficiaryNames() {
        return isAuthenticated() && !hasRole(RoleName.STUDENT_VOLUNTEER);
    }

    // --- Module 6: dashboard ---

    /**
     * The landing dashboard is open to every authenticated role — it is the screen users arrive on
     * after signing in. It carries no data a role cannot already reach: proposal counts are scoped
     * by {@code ProgramService}, and the activity feed is attached only when
     * {@link #canViewActivityLogs()} passes.
     */
    public boolean canViewDashboard() {
        return isAuthenticated();
    }

    /**
     * The academic-period calendar. Open to everyone authenticated: it is a list of semester labels
     * and dates, holding no program, person or community data of its own. Faculty need it to filter
     * their own proposal list by term even though they cannot open the M&amp;E dashboard.
     */
    public boolean canViewAcademicPeriods() {
        return isAuthenticated();
    }

    /**
     * The M&amp;E dashboard (spec Module 6: "aggregate view ... for coordinators and administrators").
     *
     * <p>Deliberately narrower than {@link #canViewDashboard()}, which stays open to everyone. That
     * screen scopes its proposal counts to the caller; this one cannot — a campus-wide beneficiary
     * total is only meaningful unscoped. The decisive part is the program completion table: it names
     * programs, and a faculty member reading it would see rows that {@code ProgramAccessPolicy} then
     * refuses to open. Rather than render a table half of whose links 404, the whole M&amp;E payload
     * is withheld and the client keeps showing them their personal overview.
     */
    public boolean canViewMonitoringDashboard() {
        return hasAnyRole(RoleName.ADMIN,
                RoleName.CAMPUS_ADMIN,
                RoleName.CAMPUS_EXTENSION_COORDINATOR,
                RoleName.EXTENSION_COORDINATOR);
    }

    // --- shared helpers (used by the checks above and by future module policies) ---

    private boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(authentication.getPrincipal()));
    }

    public boolean hasRole(RoleName role) {
        return hasAuthority(role.authority());
    }

    public boolean hasAnyRole(RoleName... roles) {
        return Arrays.stream(roles).anyMatch(this::hasRole);
    }

    private boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }
}
