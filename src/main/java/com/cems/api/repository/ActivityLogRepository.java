package com.cems.api.repository;

import com.cems.api.entity.ActivityLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityLogRepository
        extends JpaRepository<ActivityLog, String>, JpaSpecificationExecutor<ActivityLog> {

    /**
     * Newest-first slice for the dashboard feed. Returns a {@code List} rather than a {@code Page}
     * deliberately — a page would add a {@code COUNT(*)} over the whole audit trail on every
     * dashboard load, and nothing renders a total here.
     */
    @Query("SELECT l FROM ActivityLog l ORDER BY l.createdAt DESC")
    List<ActivityLog> findRecent(Pageable pageable);
}
