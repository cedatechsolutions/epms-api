package com.cems.api.repository;

import com.cems.api.entity.Community;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CommunityRepository
        extends JpaRepository<Community, String>, JpaSpecificationExecutor<Community> {

    Optional<Community> findByIdAndDeletedAtIsNull(String id);

    long countByDeletedAtIsNull();
}
