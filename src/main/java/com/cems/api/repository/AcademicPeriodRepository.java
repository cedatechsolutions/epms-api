package com.cems.api.repository;

import com.cems.api.entity.AcademicPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AcademicPeriodRepository extends JpaRepository<AcademicPeriod, String> {

    /** Calendar order — the order the period selector lists them in. */
    List<AcademicPeriod> findAllByOrderByStartsOnAsc();

    /** Newest first; the fallback when today falls in a gap and no row is flagged current. */
    Optional<AcademicPeriod> findFirstByOrderByStartsOnDesc();

    Optional<AcademicPeriod> findFirstByIsCurrentTrueOrderByStartsOnDesc();
}
