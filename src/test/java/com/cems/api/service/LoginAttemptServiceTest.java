package com.cems.api.service;

import com.cems.api.entity.User;
import com.cems.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the lockout threshold logic (spec §4: 5 failures → locked 15 minutes). */
@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    @Mock
    private UserRepository userRepository;

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService(userRepository, 5, 15);
    }

    @Test
    void belowThresholdIncrementsCounterWithoutLocking() {
        User user = userWithAttempts("u1", 2);
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        Instant lockedUntil = service.registerFailedAttempt("u1");

        assertNull(lockedUntil);
        assertEquals(3, user.getFailedAttempts());
        assertNull(user.getLockedUntil());
        verify(userRepository).save(user);
    }

    @Test
    void reachingThresholdLocksAccountAndResetsCounter() {
        User user = userWithAttempts("u1", 4);
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        Instant lockedUntil = service.registerFailedAttempt("u1");

        assertNotNull(lockedUntil);
        assertNotNull(user.getLockedUntil());
        assertEquals(0, user.getFailedAttempts());
        verify(userRepository).save(user);
    }

    @Test
    void unknownUserIsIgnored() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertNull(service.registerFailedAttempt("ghost"));
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private User userWithAttempts(String id, int attempts) {
        User user = new User();
        user.setId(id);
        user.setFailedAttempts(attempts);
        return user;
    }
}
