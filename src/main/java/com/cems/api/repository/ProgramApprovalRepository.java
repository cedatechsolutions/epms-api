package com.cems.api.repository;

import com.cems.api.entity.ProgramApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProgramApprovalRepository extends JpaRepository<ProgramApproval, String> {

    /** Chronological order — the approval stepper reads the chain forwards. */
    List<ProgramApproval> findByProgramIdOrderByActedAtAsc(String programId);
}
