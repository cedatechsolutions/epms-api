package com.cems.api.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleNameTest {

    @Test
    void codeAndAuthorityAndDisplayNameAreDerivedConsistently() {
        assertEquals("campus_extension_coordinator", RoleName.CAMPUS_EXTENSION_COORDINATOR.code());
        assertEquals("ROLE_CAMPUS_EXTENSION_COORDINATOR", RoleName.CAMPUS_EXTENSION_COORDINATOR.authority());
        assertEquals("Campus Extension Coordinator", RoleName.CAMPUS_EXTENSION_COORDINATOR.displayName());
    }

    @Test
    void authorityForCodeMatchesEnumAuthority() {
        assertEquals(RoleName.ADMIN.authority(), RoleName.authorityForCode("admin"));
        assertEquals(RoleName.FACULTY.authority(), RoleName.authorityForCode("faculty"));
    }

    @Test
    void fromCodeIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(RoleName.ADMIN, RoleName.fromCode("ADMIN"));
        assertEquals(RoleName.STUDENT_VOLUNTEER, RoleName.fromCode(" student_volunteer "));
        assertThrows(IllegalArgumentException.class, () -> RoleName.fromCode("root"));
    }

    @Test
    void isValidCodeReflectsTheSixDomainRoles() {
        assertTrue(RoleName.isValidCode("extension_coordinator"));
        assertFalse(RoleName.isValidCode("ROLE_ADMIN"));
        assertFalse(RoleName.isValidCode(null));
        assertEquals(6, RoleName.values().length);
    }
}
