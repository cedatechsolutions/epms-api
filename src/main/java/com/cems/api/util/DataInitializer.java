package com.cems.api.util;

import com.cems.api.entity.Role;
import com.cems.api.entity.User;
import com.cems.api.repository.RoleRepository;
import com.cems.api.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(RoleRepository roleRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        createRoleIfMissing("ROLE_USER", "Standard application user");
        Role adminRole = createRoleIfMissing("ROLE_ADMIN", "Administrator with elevated privileges");
        Role superAdminRole = createRoleIfMissing("ROLE_SUPER_ADMIN", "Super administrator with full user management access");

        createUserIfMissing(
                "superadmin@cems.com",
                "SuperAdmin123!",
                "Super",
                "Admin",
                "System",
                "09170000000",
                Set.of(superAdminRole));

        createUserIfMissing(
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

    private void createUserIfMissing(String email,
            String rawPassword,
            String firstName,
            String lastName,
            String middleName,
            String contactNumber,
            Set<Role> roles) {
        if (userRepository.existsByEmail(email)) {
            return;
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setMiddleName(middleName);
        user.setContactNumber(contactNumber);
        user.setRoles(new HashSet<>(roles));
        userRepository.save(user);
    }
}
