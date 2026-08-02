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
