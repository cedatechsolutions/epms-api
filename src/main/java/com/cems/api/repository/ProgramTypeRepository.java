package com.cems.api.repository;

import com.cems.api.entity.ProgramType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProgramTypeRepository extends JpaRepository<ProgramType, String> {

    List<ProgramType> findAllByOrderByNameAsc();

    /** Candidates for a scoring run — retired types are excluded from future runs only. */
    List<ProgramType> findByActiveTrueOrderByNameAsc();

    Optional<ProgramType> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
