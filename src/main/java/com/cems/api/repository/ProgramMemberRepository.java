package com.cems.api.repository;

import com.cems.api.entity.ProgramMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProgramMemberRepository extends JpaRepository<ProgramMember, String> {

    List<ProgramMember> findByProgramIdOrderByCreatedAtAsc(String programId);

    Optional<ProgramMember> findByProgramIdAndUserId(String programId, String userId);

    boolean existsByProgramIdAndUserId(String programId, String userId);

    /**
     * The program ids a user is assigned to. Fetched once and folded into the list Specification,
     * so a restricted role's list query stays a single statement rather than a subquery per row.
     */
    @Query("""
            SELECT m.program.id
            FROM ProgramMember m
            WHERE m.userId = :userId
              AND m.program.deletedAt IS NULL
            """)
    List<String> findProgramIdsByUserId(@Param("userId") String userId);
}
