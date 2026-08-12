package com.cems.api.dto;

import com.cems.api.entity.Program;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Program list-row projection (spec Module 5 §1: title, community, faculty lead, type, status,
 * scheduled date). {@code facultyLeadName} is resolved in the service.
 */
public record ProgramSummaryResponse(
        String id,
        String title,
        String communityId,
        String communityName,
        String programTypeId,
        String programTypeName,
        String facultyLeadId,
        String facultyLeadName,
        String status,
        LocalDate proposedDate,
        Instant createdAt,
        Instant updatedAt) {

    public static ProgramSummaryResponse fromEntity(Program program, String facultyLeadName) {
        return new ProgramSummaryResponse(
                program.getId(),
                program.getTitle(),
                program.getCommunity() == null ? null : program.getCommunity().getId(),
                program.getCommunity() == null ? null : program.getCommunity().getName(),
                program.getProgramType() == null ? null : program.getProgramType().getId(),
                program.getProgramType() == null ? null : program.getProgramType().getName(),
                program.getFacultyLeadId(),
                facultyLeadName,
                program.getStatus(),
                program.getProposedDate(),
                program.getCreatedAt(),
                program.getUpdatedAt());
    }
}
