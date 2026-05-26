package com.cems.api.util;

import com.cems.api.entity.Role;
import com.cems.api.entity.User;
import com.cems.api.repository.RoleRepository;
import com.cems.api.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class DataInitializer implements CommandLineRunner {

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

        createRoleIfMissing("ROLE_USER", "Standard application user");
        Role adminRole = createRoleIfMissing("ROLE_ADMIN", "Administrator with elevated privileges");
        Role superAdminRole = createRoleIfMissing("ROLE_SUPER_ADMIN", "Super administrator with full user management access");

        createOrSyncBootstrapUser(
                "superadmin@cems.com",
                "SuperAdmin123!",
                "Super",
                "Admin",
                "System",
                "09170000000",
                Set.of(superAdminRole));

        createOrSyncBootstrapUser(
                "admin@cems.com",
                "Admin123!",
                "Default",
                "Admin",
                "Operations",
                "09170000001",
                Set.of(adminRole));
    }

    private Role createRoleIfMissing(String roleName, String description) {
        return roleRepository.findByName(roleName)
                .orElseGet(() -> {
                    Role role = new Role();
                    role.setName(roleName);
                    role.setDescription(description);
                    return roleRepository.save(role);
                });
    }

    private void createOrSyncBootstrapUser(String email,
            String rawPassword,
            String firstName,
            String lastName,
            String middleName,
            String contactNumber,
            Set<Role> roles) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            user = new User();
            user.setEmail(email);
        } else if (!syncBootstrapUsers) {
            return;
        }

        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setMiddleName(middleName);
        user.setContactNumber(contactNumber);
        user.setActive(true);
        user.setRoles(new HashSet<>(roles));
        userRepository.save(user);
    }
}
