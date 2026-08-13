package com.cems.api.util;

import com.cems.api.entity.Role;
import com.cems.api.entity.User;
import com.cems.api.repository.RoleRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.security.RoleName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Creates a single admin account on an otherwise empty deployment.
 *
 * <p>{@link DataInitializer} seeds one account per role for local development but is disabled in
 * prod ({@code app.seed.enabled=false}), and no migration inserts users. Without this, a fresh
 * production database has no way to log in. This runner closes that gap without putting credentials
 * in the repository: it acts only when {@code APP_BOOTSTRAP_ADMIN_EMAIL} and
 * {@code APP_BOOTSTRAP_ADMIN_PASSWORD} are both present in the environment.
 *
 * <p>It is deliberately one-shot and non-destructive. If an account with that email already exists
 * it is left untouched — re-running with the variables still set will never reset a live admin's
 * password, so the intended operational flow is to set both variables for the first boot and then
 * remove them. The created account is flagged {@code mustChangePassword}, so the bootstrap password
 * is only ever good for the first login.
 */
@Component
public class BootstrapAdminInitializer implements CommandLineRunner {

    /**
     * Minimum bootstrap password length. Higher than a typical user-facing minimum because this
     * credential is set once, by hand, for the most privileged account in the system.
     */
    static final int MIN_PASSWORD_LENGTH = 12;

    private static final Logger logger = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public BootstrapAdminInitializer(UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap-admin.email:}") String email,
            @Value("${app.bootstrap-admin.password:}") String password) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    public void run(String... args) {
        if (email.isEmpty() && password.isEmpty()) {
            return;
        }

        // Half-configured is always a mistake — failing fast beats silently starting with no admin.
        if (email.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException(
                    "Bootstrap admin is half-configured. Set both APP_BOOTSTRAP_ADMIN_EMAIL and "
                            + "APP_BOOTSTRAP_ADMIN_PASSWORD, or neither.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            logger.info("Bootstrap admin '{}' already exists; leaving it untouched. "
                    + "Unset APP_BOOTSTRAP_ADMIN_EMAIL/PASSWORD now that the account is provisioned.", email);
            return;
        }

        Role adminRole = roleRepository.findByName(RoleName.ADMIN.code())
                .orElseThrow(() -> new IllegalStateException(
                        "Role '" + RoleName.ADMIN.code() + "' is missing. Flyway migrations must run "
                                + "before the bootstrap admin can be created."));

        User admin = new User();
        admin.setEmail(email);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setFirstName("System");
        admin.setLastName("Administrator");
        admin.setActive(true);
        admin.setMustChangePassword(true);
        admin.setRoles(new HashSet<>(Set.of(adminRole)));
        userRepository.save(admin);

        logger.warn("Created bootstrap admin '{}'. Log in, change the password immediately, then "
                + "remove APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD from the environment.", email);
    }
}
