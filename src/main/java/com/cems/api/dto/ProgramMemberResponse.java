package com.cems.api.dto;

import com.cems.api.entity.ProgramMember;

import java.time.Instant;

/**
 * Someone assigned to a program (spec Module 5 §1). {@code name} is resolved in the service so the
 * client never has to join users itself.
 */
public record ProgramMemberResponse(
        String id,
        String programId,
        String userId,
        String name,
        String email,
        String roleInProgram,
        String assignedBy,
        Instant createdAt) {

    public static ProgramMemberResponse fromEntity(ProgramMember member, String name, String email) {
        return new ProgramMemberResponse(
                member.getId(),
                member.getProgram() == null ? null : member.getProgram().getId(),
                member.getUserId(),
                name,
                email,
                member.getRoleInProgram(),
                member.getAssignedBy(),
                member.getCreatedAt());
    }
}
