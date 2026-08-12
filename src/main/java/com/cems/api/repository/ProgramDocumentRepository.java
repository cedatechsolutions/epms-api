package com.cems.api.repository;

import com.cems.api.entity.ProgramDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProgramDocumentRepository extends JpaRepository<ProgramDocument, String> {

    List<ProgramDocument> findByProgramIdOrderByCreatedAtDesc(String programId);

    Optional<ProgramDocument> findByIdAndProgramId(String id, String programId);

    /**
     * Backs the non-blocking "no needs assessment linked" warning shown when submitting
     * (spec Module 5 AC 2) — a linked report satisfies it just as a linked survey does.
     */
    boolean existsByProgramIdAndDocType(String programId, String docType);
}
