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

    // --- shared helpers (used by the checks above and by future module policies) ---

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
