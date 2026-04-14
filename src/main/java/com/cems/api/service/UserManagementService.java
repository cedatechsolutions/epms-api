package com.cems.api.service;

import com.cems.api.dto.CreateUserRequest;
import com.cems.api.dto.UpdateUserRequest;
import com.cems.api.dto.UserResponse;
import com.cems.api.entity.Role;
import com.cems.api.entity.User;
import com.cems.api.repository.RoleRepository;
import com.cems.api.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class UserManagementService {

    public static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("Authenticated user not found."));
        return UserResponse.fromEntity(user);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll(Sort.by("lastName", "firstName", "email")).stream()
                .map(UserResponse::fromEntity)
                .toList();
    }

    public UserResponse createManagedUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email is already in use.");
        }

        Role role = resolveManageableRole(request.getRole());

        User user = new User();
        applyProfile(user,
                request.getEmail(),
                request.getFirstName(),
                request.getLastName(),
                request.getMiddleName(),
                request.getContactNumber());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRoles(new HashSet<>(Set.of(role)));

        return UserResponse.fromEntity(userRepository.save(user));
    }

    public UserResponse updateManagedUser(String userId, UpdateUserRequest request) {
        User user = findManageableUser(userId);

        if (!user.getEmail().equalsIgnoreCase(request.getEmail()) && userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email is already in use.");
        }

        Role role = resolveManageableRole(request.getRole());

        applyProfile(user,
                request.getEmail(),
                request.getFirstName(),
                request.getLastName(),
                request.getMiddleName(),
                request.getContactNumber());

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        user.setRoles(new HashSet<>(Set.of(role)));
        return UserResponse.fromEntity(userRepository.save(user));
    }

    public void deleteManagedUser(String userId) {
        User user = findManageableUser(userId);
        userRepository.delete(user);
    }

    private void applyProfile(User user,
            String email,
            String firstName,
            String lastName,
            String middleName,
            String contactNumber) {
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setMiddleName(middleName);
        user.setContactNumber(contactNumber);
    }

    private User findManageableUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found."));

        if (hasRole(user, ROLE_SUPER_ADMIN)) {
            throw new IllegalArgumentException("Super admin accounts cannot be managed through this endpoint.");
        }

        return user;
    }

    private Role resolveManageableRole(String requestedRole) {
        String normalizedRole = requestedRole.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedRole) {
            case "ADMIN" -> findRole(ROLE_ADMIN);
            case "USER" -> findRole(ROLE_USER);
            default -> throw new IllegalArgumentException("Role must be either ADMIN or USER.");
        };
    }

    private Role findRole(String roleName) {
        return roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Required role " + roleName + " is missing."));
    }

    private boolean hasRole(User user, String roleName) {
        return user.getRoles().stream()
                .anyMatch(role -> roleName.equals(role.getName()));
    }
}
