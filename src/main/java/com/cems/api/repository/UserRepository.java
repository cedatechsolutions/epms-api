package com.cems.api.repository;

import com.cems.api.entity.User;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByDeletedAtIsNull(Sort sort);

    long countByDeletedAtIsNull();

    long countByActiveAndDeletedAtIsNull(boolean active);

    /** Active holders of a role, used to fill report signatory blocks (spec Module 3 §5). */
    List<User> findByRoles_NameAndActiveTrueAndDeletedAtIsNull(String roleName);

    /** Every account that can still be named in a workflow, for people pickers (spec Module 5 §2). */
    List<User> findByActiveTrueAndDeletedAtIsNull(Sort sort);
}
