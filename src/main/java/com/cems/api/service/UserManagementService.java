package com.cems.api.service;

import com.cems.api.dto.CreateUserRequest;
import com.cems.api.dto.UserListQuery;
import com.cems.api.dto.UpdateUserRequest;
import com.cems.api.dto.UserResponse;
import com.cems.api.dto.UserStatsResponse;
import com.cems.api.entity.Role;
import com.cems.api.entity.User;
import com.cems.api.exception.ConflictException;
import com.cems.api.security.RoleName;
import com.cems.api.security.TokenHasher;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.cems.api.repository.RoleRepository;
import com.cems.api.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class UserManagementService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final ActivityLogService activityLogService;
    private final EmailService emailService;
    private final TokenHasher tokenHasher;

    public UserManagementService(UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            ActivityLogService activityLogService,
            EmailService emailService,
            TokenHasher tokenHasher) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.activityLogService = activityLogService;
        this.emailService = emailService;
        this.tokenHasher = tokenHasher;
    }

    public UserResponse getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("Authenticated user not found."));
        return UserResponse.fromEntity(user);
    }

    public UserResponse getUserById(String userId) {
        return UserResponse.fromEntity(findActiveUser(userId));
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findByDeletedAtIsNull(Sort.by("lastName", "firstName", "email")).stream()
                .map(UserResponse::fromEntity)
                .toList();
    }

    public UserStatsResponse getUserStats() {
        long total = userRepository.countByDeletedAtIsNull();
        long active = userRepository.countByActiveAndDeletedAtIsNull(true);
        return new UserStatsResponse(total, active, total - active);
    }

    public Page<UserResponse> getUsers(UserListQuery query) {
        int normalizedPage = normalizePage(query.getPage());
        int pageSize = normalizePageSize(query.getPerPage());
        Sort sort = resolveSort(query.getSort(), query.getDirection());
        Specification<User> specification = buildUserSpecification(query);
        Pageable pageable = PageRequest.of(
                normalizedPage,
                pageSize,
                sort);

        Page<User> page = userRepository.findAll(specification, pageable);
        if (page.isEmpty() && page.getTotalPages() > 0 && normalizedPage >= page.getTotalPages()) {
            page = userRepository.findAll(specification, PageRequest.of(page.getTotalPages() - 1, pageSize, sort));
        }

        return page.map(UserResponse::fromEntity);
    }

    public UserResponse createManagedUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email is already in use.");
        }

        Role role = resolveManageableRole(request.getRole());

        User user = new User();
        applyProfile(user,
                request.getEmail(),
                request.getFirstName(),
                request.getLastName(),
                request.getMiddleName(),
                request.getContactNumber());
        user.setActive(resolveManagedStatus(request.getStatus(), true));
        user.setRoles(new HashSet<>(Set.of(role)));

        // Admin may set a password, or leave it blank to email a temporary one that must be
        // changed on first login (spec Module 1 §4).
        boolean adminSetPassword = request.getPassword() != null && !request.getPassword().isBlank();
        String rawPassword = adminSetPassword ? request.getPassword() : generateTemporaryPassword();
        if (adminSetPassword && rawPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setMustChangePassword(!adminSetPassword);

        User saved = userRepository.save(user);
        if (!adminSetPassword) {
            emailService.sendTemporaryPasswordEmail(saved.getEmail(), rawPassword);
        }
        activityLogService.record("user.created", "user", saved.getId(),
                Map.of("email", saved.getEmail(), "role", role.getName()));
        return UserResponse.fromEntity(saved);
    }

    /** 12-character URL-safe random temporary password (satisfies the 8-char minimum). */
    private String generateTemporaryPassword() {
        return tokenHasher.generateRawToken().substring(0, 12);
    }

    public UserResponse updateManagedUser(String userId, UpdateUserRequest request) {
        User user = findManageableUser(userId);

        if (!user.getEmail().equalsIgnoreCase(request.getEmail()) && userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email is already in use.");
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

        user.setActive(resolveManagedStatus(request.getStatus(), user.isActive()));
        user.setRoles(new HashSet<>(Set.of(role)));
        User saved = userRepository.save(user);
        activityLogService.record("user.updated", "user", saved.getId(),
                Map.of("email", saved.getEmail(), "role", role.getName()));
        return UserResponse.fromEntity(saved);
    }

    public UserResponse updateManagedUserStatus(String userId, String requestedStatus) {
        User user = findManageableUser(userId);
        user.setActive(resolveManagedStatus(requestedStatus, user.isActive()));
        User saved = userRepository.save(user);
        activityLogService.record("user.status_changed", "user", saved.getId(),
                Map.of("status", saved.isActive() ? "active" : "inactive"));
        return UserResponse.fromEntity(saved);
    }

    public void resetManagedUserPassword(String userId, String password, String passwordConfirmation) {
        User user = findManageableUser(userId);
        validateManagedPassword(password, passwordConfirmation);
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);
        activityLogService.record("user.password_reset", "user", user.getId(), null);
    }

    /**
     * Soft-deletes the user (spec cross-cutting rule 3): marks {@code deleted_at} and
     * deactivates, preserving referenced audit records. Hard deletion would violate the
     * {@code activity_logs.user_id} foreign key.
     */
    public void deleteManagedUser(String userId) {
        User user = findManageableUser(userId);
        user.setDeletedAt(Instant.now());
        user.setActive(false);
        userRepository.save(user);
        activityLogService.record("user.deleted", "user", user.getId(),
                Map.of("email", user.getEmail()));
    }

    private void applyProfile(User user,
            String email,
            String firstName,
            String lastName,
            String middleName,
            String contactNumber) {
        user.setEmail(trimmed(email));
        user.setFirstName(trimmed(firstName));
        user.setLastName(trimmed(lastName));
        // Middle name and contact number are optional; store an omitted one as null rather than
        // an empty string so "no value" reads the same however the client sent it.
        user.setMiddleName(trimmedOrNull(middleName));
        user.setContactNumber(trimmedOrNull(contactNumber));
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private String trimmedOrNull(String value) {
        String trimmed = trimmed(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private User findManageableUser(String userId) {
        return findActiveUser(userId);
    }

    private User findActiveUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found."));
        if (user.getDeletedAt() != null) {
            throw new NoSuchElementException("User not found.");
        }
        return user;
    }

    private Role resolveManageableRole(String requestedRole) {
        if (requestedRole == null || requestedRole.isBlank()) {
            throw new IllegalArgumentException("Role is required.");
        }
        if (!RoleName.isValidCode(requestedRole)) {
            throw new IllegalArgumentException("Role must be one of: " + RoleName.codesCsv() + ".");
        }
        return findRole(RoleName.fromCode(requestedRole).code());
    }

    private Role findRole(String roleName) {
        return roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Required role " + roleName + " is missing."));
    }

    private boolean resolveManagedStatus(String requestedStatus, boolean defaultValue) {
        if (requestedStatus == null || requestedStatus.isBlank()) {
            return defaultValue;
        }

        String normalizedStatus = requestedStatus.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedStatus) {
            case "ACTIVE" -> true;
            case "INACTIVE" -> false;
            default -> throw new IllegalArgumentException("Status must be either ACTIVE or INACTIVE.");
        };
    }

    private void validateManagedPassword(String password, String passwordConfirmation) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is required.");
        }

        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }

        if (passwordConfirmation == null || passwordConfirmation.isBlank()) {
            throw new IllegalArgumentException("Password confirmation is required.");
        }

        if (!password.equals(passwordConfirmation)) {
            throw new IllegalArgumentException("Password confirmation must match password.");
        }
    }

    private Specification<User> buildUserSpecification(UserListQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Exclude soft-deleted users from all list results (cross-cutting rule 3).
            predicates.add(criteriaBuilder.isNull(root.get("deletedAt")));

            if (query.getSearch() != null && !query.getSearch().isBlank()) {
                String search = "%" + query.getSearch().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), search),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("firstName")), search),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("lastName")), search),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("middleName")), search),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("contactNumber")), search)));
            }

            if (query.getStatus() != null && !query.getStatus().isBlank()) {
                boolean active = switch (query.getStatus().trim().toUpperCase(Locale.ROOT)) {
                    case "ACTIVE" -> true;
                    case "INACTIVE" -> false;
                    default -> throw new IllegalArgumentException("Status filter must be either ACTIVE or INACTIVE.");
                };
                predicates.add(criteriaBuilder.equal(root.get("active"), active));
            }

            if (query.getRole() != null && !query.getRole().isBlank()) {
                String requestedRole = query.getRole().trim();
                if (!RoleName.isValidCode(requestedRole)) {
                    throw new IllegalArgumentException("Role filter must be one of: " + RoleName.codesCsv() + ".");
                }
                Join<User, Role> roleJoin = root.join("roles", JoinType.INNER);
                predicates.add(criteriaBuilder.equal(roleJoin.get("name"), RoleName.fromCode(requestedRole).code()));
                criteriaQuery.distinct(true);
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private int normalizePage(int page) {
        return Math.max(0, page - 1);
    }

    private int normalizePageSize(int perPage) {
        return Math.min(100, Math.max(1, perPage));
    }

    private Sort resolveSort(String requestedSort, String requestedDirection) {
        String sortProperty = switch (requestedSort == null ? "" : requestedSort.trim()) {
            case "email" -> "email";
            case "firstName" -> "firstName";
            case "middleName" -> "middleName";
            case "contactNumber" -> "contactNumber";
            case "active" -> "active";
            case "lastLoginAt" -> "lastLoginAt";
            case "createdAt" -> "createdAt";
            case "updatedAt" -> "updatedAt";
            case "lastName", "" -> "lastName";
            default -> throw new IllegalArgumentException("Unsupported sort field.");
        };

        Sort.Direction direction = "desc".equalsIgnoreCase(requestedDirection)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        Sort sort = Sort.by(direction, sortProperty);
        if (!"lastName".equals(sortProperty)) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, "lastName"));
        }
        return sort.and(Sort.by(Sort.Direction.ASC, "firstName", "email"));
    }
}
