package com.cems.api.util;

import com.cems.api.entity.Role;
import com.cems.api.entity.User;
import com.cems.api.repository.RoleRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.security.RoleName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the one-shot production admin bootstrap: it must act only when fully configured,
 * refuse weak credentials, and never overwrite an account that already exists.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BootstrapAdminInitializerTest {

    private static final String EMAIL = "admin@cems.example";
    private static final String PASSWORD = "bootstrap-admin-password";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private BootstrapAdminInitializer initializer(String email, String password) {
        return new BootstrapAdminInitializer(userRepository, roleRepository, passwordEncoder, email, password);
    }

    @Test
    void doesNothingWhenNeitherVariableIsSet() {
        initializer("", "").run();

        verifyNoInteractions(userRepository, roleRepository, passwordEncoder);
    }

    @Test
    void failsFastWhenOnlyEmailIsSet() {
        BootstrapAdminInitializer initializer = initializer(EMAIL, "");

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::run);

        assertTrue(ex.getMessage().contains("half-configured"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void failsFastWhenOnlyPasswordIsSet() {
        BootstrapAdminInitializer initializer = initializer("", PASSWORD);

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::run);

        assertTrue(ex.getMessage().contains("half-configured"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsPasswordShorterThanTheMinimum() {
        BootstrapAdminInitializer initializer = initializer(EMAIL, "short");

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::run);

        assertTrue(ex.getMessage().contains("at least " + BootstrapAdminInitializer.MIN_PASSWORD_LENGTH));
        verify(userRepository, never()).save(any());
    }

    @Test
    void createsAdminFlaggedForPasswordChangeWhenDatabaseHasNoSuchUser() {
        Role adminRole = adminRole();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(roleRepository.findByName(RoleName.ADMIN.code())).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode(PASSWORD)).thenReturn("encoded-password");

        initializer(EMAIL, PASSWORD).run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User admin = saved.getValue();
        assertEquals(EMAIL, admin.getEmail());
        assertEquals("encoded-password", admin.getPassword());
        assertTrue(admin.isMustChangePassword(), "bootstrap password must be single-use");
        assertTrue(admin.isActive());
        assertEquals(1, admin.getRoles().size());
        assertTrue(admin.getRoles().contains(adminRole));
    }

    @Test
    void trimsSurroundingWhitespaceOnTheConfiguredEmail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(roleRepository.findByName(RoleName.ADMIN.code())).thenReturn(Optional.of(adminRole()));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");

        initializer("  " + EMAIL + "  ", PASSWORD).run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals(EMAIL, saved.getValue().getEmail());
    }

    @Test
    void leavesAnExistingAccountUntouchedSoRerunsCannotResetItsPassword() {
        User existing = new User();
        existing.setEmail(EMAIL);
        existing.setPassword("live-password-hash");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

        initializer(EMAIL, PASSWORD).run();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
        assertEquals("live-password-hash", existing.getPassword());
    }

    @Test
    void failsWhenTheAdminRoleIsMissingBecauseMigrationsHaveNotRun() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(roleRepository.findByName(RoleName.ADMIN.code())).thenReturn(Optional.empty());

        BootstrapAdminInitializer initializer = initializer(EMAIL, PASSWORD);

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::run);

        assertTrue(ex.getMessage().contains(RoleName.ADMIN.code()));
        verify(userRepository, never()).save(any());
    }

    private Role adminRole() {
        Role role = new Role();
        role.setName(RoleName.ADMIN.code());
        return role;
    }
}
