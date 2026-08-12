package com.cems.api.dto;

import com.cems.api.entity.ProgramApproval;

import java.time.Instant;

/**
 * One row of the approval audit trail, as rendered by the workflow stepper (spec Module 5 §2).
 * {@code actedByName} is resolved in the service so the frontend never has to join users itself.
 */
public record ProgramApprovalResponse(
        String id,
        int stage,
        String stageRole,
        String action,
        String actedBy,
        String actedByName,
        String comment,
        Instant actedAt) {

    public static ProgramApprovalResponse fromEntity(ProgramApproval approval, String actedByName) {
        return new ProgramApprovalResponse(
                approval.getId(),
                approval.getStage(),
                approval.getStageRole(),
                approval.getAction(),
                approval.getActedBy(),
                actedByName,
                approval.getComment(),
                approval.getActedAt());
    }
}
