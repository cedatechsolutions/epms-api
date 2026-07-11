package com.cems.api.util;

import com.cems.api.entity.Role;
import com.cems.api.entity.User;
import com.cems.api.repository.RoleRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.security.RoleName;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Seeds the six EPMS domain roles (spec §2.1) and one bootstrap account per role for
 * local/dev use. Role rows normally already exist via Flyway migration {@code V2}; this
 * seeder is idempotent and simply reuses them by code.
 *
 * <p>Documented dev credentials (local profile only — {@code app.seed.enabled=false} in prod):
 * <pre>
 *   admin@cems.com               / Admin123!         (admin)
 *   campus.admin@cems.com        / CampusAdmin123!   (campus_admin)
 *   campus.coordinator@cems.com  / CampusCoord123!   (campus_extension_coordinator)
 *   coordinator@cems.com         / Coordinator123!   (extension_coordinator)
 *   faculty@cems.com             / Faculty123!       (faculty)
 *   student@cems.com             / Student123!       (student_volunteer)
 * </pre>
 */
@Component
public class DataInitializer implements CommandLineRunner {

    /** Description shown in the roles table when the seeder has to create a role row. */
    private record BootstrapAccount(RoleName role, String email, String rawPassword,
            String firstName, String lastName, String middleName, String contactNumber) {
    }

    private static final List<BootstrapAccount> BOOTSTRAP_ACCOUNTS = List.of(
            new BootstrapAccount(RoleName.ADMIN, "admin@cems.com", "Admin123!",
                    "System", "Administrator", "Root", "09170000000"),
            new BootstrapAccount(RoleName.CAMPUS_ADMIN, "campus.admin@cems.com", "CampusAdmin123!",
                    "Campus", "Administrator", "Approval", "09170000001"),
            new BootstrapAccount(RoleName.CAMPUS_EXTENSION_COORDINATOR, "campus.coordinator@cems.com", "CampusCoord123!",
                    "Campus", "Coordinator", "Extension", "09170000002"),
            new BootstrapAccount(RoleName.EXTENSION_COORDINATOR, "coordinator@cems.com", "Coordinator123!",
                    "Extension", "Coordinator", "Programs", "09170000003"),
            new BootstrapAccount(RoleName.FACULTY, "faculty@cems.com", "Faculty123!",
                    "Faculty", "Extensionist", "Leader", "09170000004"),
            new BootstrapAccount(RoleName.STUDENT_VOLUNTEER, "student@cems.com", "Student123!",
                    "Student", "Volunteer", "Assist", "09170000005"));

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean seedEnabled;
    private final boolean syncBootstrapUsers;

    public DataInitializer(RoleRepository roleRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.enabled:true}") boolean seedEnabled,
            @Value("${app.seed.sync-bootstrap-users:true}") boolean syncBootstrapUsers) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
        this.syncBootstrapUsers = syncBootstrapUsers;
    }

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            return;
        }

        for (BootstrapAccount account : BOOTSTRAP_ACCOUNTS) {
            Role role = resolveRole(account.role());
            createOrSyncBootstrapUser(account, role);
        }
    }

    private Role resolveRole(RoleName roleName) {
        return roleRepository.findByName(roleName.code())
                .orElseGet(() -> {
                    Role role = new Role();
                    role.setName(roleName.code());
                    role.setDescription(roleName.code());
                    return roleRepository.save(role);
                });
    }

    private void createOrSyncBootstrapUser(BootstrapAccount account, Role role) {
        User user = userRepository.findByEmail(account.email()).orElse(null);
        if (user == null) {
            user = new User();
            user.setEmail(account.email());
        } else if (!syncBootstrapUsers) {
            return;
        }

        user.setPassword(passwordEncoder.encode(account.rawPassword()));
        user.setFirstName(account.firstName());
        user.setLastName(account.lastName());
        user.setMiddleName(account.middleName());
        user.setContactNumber(account.contactNumber());
        user.setActive(true);
        user.setMustChangePassword(false);
        user.setRoles(new HashSet<>(Set.of(role)));
        userRepository.save(user);
    }
}
