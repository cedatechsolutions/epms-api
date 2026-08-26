package com.cems.api.dto;

import com.cems.api.entity.User;
import com.cems.api.entity.Role;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A person as an option in a picker — currently the proposal form's faculty-lead select
 * (spec Module 5 §2).
 *
 * <p>Deliberately narrower than {@link UserResponse}: name, email and roles only. The directory is
 * readable by every proposal author (faculty included), not just the administrator, so it must not
 * carry account state — {@code active}, {@code mustChangePassword}, {@code lastLoginAt} and the
 * timestamps stay behind {@code canManageUsers()}. Widening this record widens that exposure.
 */
public record UserOptionResponse(String id, String name, String email, Set<String> roles) {

    public static UserOptionResponse fromEntity(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return new UserOptionResponse(
                user.getId(),
                name.isEmpty() ? user.getEmail() : name,
                user.getEmail(),
                user.getRoles().stream()
                        .map(Role::getName)
                        .sorted()
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }
}
