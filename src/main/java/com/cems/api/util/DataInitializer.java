package com.cems.api.util;

import com.cems.api.entity.Community;
import com.cems.api.entity.Role;
import com.cems.api.entity.Sector;
import com.cems.api.entity.User;
import com.cems.api.repository.CommunityRepository;
import com.cems.api.repository.RoleRepository;
import com.cems.api.repository.SectorRepository;
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
    private final CommunityRepository communityRepository;
    private final SectorRepository sectorRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean seedEnabled;
    private final boolean syncBootstrapUsers;

    public DataInitializer(RoleRepository roleRepository,
            UserRepository userRepository,
            CommunityRepository communityRepository,
            SectorRepository sectorRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.enabled:true}") boolean seedEnabled,
            @Value("${app.seed.sync-bootstrap-users:true}") boolean syncBootstrapUsers) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.communityRepository = communityRepository;
        this.sectorRepository = sectorRepository;
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

        seedSampleCommunities();
    }

    /**
     * Inserts two sample partner communities (spec §5.4) so the Module 2 list/detail screens render
     * with data on first run. Idempotent: skipped once any community exists. Sectors themselves come
     * from Flyway migration {@code V4}, not this seeder.
     */
    private void seedSampleCommunities() {
        if (communityRepository.count() > 0) {
            return;
        }

        String creatorId = userRepository.findByEmail("coordinator@cems.com")
                .map(User::getId).orElse(null);

        Community mabolo = new Community();
        mabolo.setName("Barangay Mabolo");
        mabolo.setBarangayCode("BAC-013");
        mabolo.setMunicipality("Bacoor");
        mabolo.setProvince("Cavite");
        mabolo.setClassification("urban_poor");
        mabolo.setEstimatedPopulation(4200);
        mabolo.setHouseholdCount(980);
        mabolo.setPopulationMale(2050);
        mabolo.setPopulationFemale(2150);
        mabolo.setContactPersonName("Brgy. Capt. Elena Reyes");
        mabolo.setContactPersonDesignation("Barangay Captain");
        mabolo.setContactPersonPhone("09171234567");
        mabolo.setNotes("Priority urban-poor partner community near the campus.");
        mabolo.setCreatedBy(creatorId);
        mabolo.setSectors(sectorsByName("Women", "4Ps", "OSY"));
        communityRepository.save(mabolo);

        Community sineguelasan = new Community();
        sineguelasan.setName("Barangay Sineguelasan");
        sineguelasan.setBarangayCode("BAC-020");
        sineguelasan.setMunicipality("Bacoor");
        sineguelasan.setProvince("Cavite");
        sineguelasan.setClassification("coastal");
        sineguelasan.setEstimatedPopulation(6800);
        sineguelasan.setHouseholdCount(1550);
        sineguelasan.setPopulationMale(3400);
        sineguelasan.setPopulationFemale(3400);
        sineguelasan.setContactPersonName("Brgy. Capt. Mario Santos");
        sineguelasan.setContactPersonDesignation("Barangay Captain");
        sineguelasan.setContactPersonPhone("09189876543");
        sineguelasan.setNotes("Coastal community with active fisherfolk association.");
        sineguelasan.setCreatedBy(creatorId);
        sineguelasan.setSectors(sectorsByName("Farmers/Fisherfolk", "Senior Citizens", "Youth"));
        communityRepository.save(sineguelasan);
    }

    private Set<Sector> sectorsByName(String... names) {
        Set<Sector> sectors = new HashSet<>();
        for (Sector sector : sectorRepository.findAll()) {
            if (Set.of(names).contains(sector.getName())) {
                sectors.add(sector);
            }
        }
        return sectors;
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
