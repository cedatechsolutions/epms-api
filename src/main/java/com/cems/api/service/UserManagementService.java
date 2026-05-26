package com.cems.api.service;

import com.cems.api.dto.CreateUserRequest;
import com.cems.api.dto.UserListQuery;
import com.cems.api.dto.UpdateUserRequest;
import com.cems.api.dto.UserResponse;
import com.cems.api.entity.Role;
import com.cems.api.entity.User;
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

import java.util.ArrayList;
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

    public UserResponse getUserById(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found."));
        return UserResponse.fromEntity(user);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll(Sort.by("lastName", "firstName", "email")).stream()
                .map(UserResponse::fromEntity)
                .toList();
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
        user.setActive(resolveManagedStatus(request.getStatus(), true));
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

        user.setActive(resolveManagedStatus(request.getStatus(), user.isActive()));
        user.setRoles(new HashSet<>(Set.of(role)));
        return UserResponse.fromEntity(userRepository.save(user));
    }

    public UserResponse updateManagedUserStatus(String userId, String requestedStatus) {
        User user = findManageableUser(userId);
        user.setActive(resolveManagedStatus(requestedStatus, user.isActive()));
        return UserResponse.fromEntity(userRepository.save(user));
    }

    public void resetManagedUserPassword(String userId, String password, String passwordConfirmation) {
        User user = findManageableUser(userId);
        validateManagedPassword(password, passwordConfirmation);
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);
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

    private boolean hasRole(User user, String roleName) {
        return user.getRoles().stream()
                .anyMatch(role -> roleName.equals(role.getName()));
    }

    private Specification<User> buildUserSpecification(UserListQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

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
                Join<User, Role> roleJoin = root.join("roles", JoinType.INNER);
                String normalizedRole = query.getRole().trim().toUpperCase(Locale.ROOT);
                Predicate rolePredicate = switch (normalizedRole) {
                    case "ADMIN" -> roleJoin.get("name").in(ROLE_ADMIN, ROLE_SUPER_ADMIN);
                    case "SUPER_ADMIN" -> criteriaBuilder.equal(roleJoin.get("name"), ROLE_SUPER_ADMIN);
                    case "USER" -> criteriaBuilder.equal(roleJoin.get("name"), ROLE_USER);
                    default -> throw new IllegalArgumentException("Role filter must be ADMIN, SUPER_ADMIN, or USER.");
                };
                predicates.add(rolePredicate);
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
