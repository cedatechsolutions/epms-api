package com.cems.api.dto;

import com.cems.api.entity.Program;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Full proposal detail (spec Module 5 §2), including the approval trail that drives the workflow
 * stepper and the attachments card.
 *
 * <p>{@code availableActions} is the server's answer to "which buttons should this user see?" — the
 * frontend must render from it rather than re-deriving the state machine, so the two can never
 * disagree. {@code canEdit} likewise reflects the server's ownership + status rules.
 *
 * <p>{@code canRecordDelivery} is the delivery-phase counterpart of {@code canEdit}: true only when
 * the caller owns the program AND it is approved or ongoing. The two are never both true — a
 * proposal is editable before submission, and deliverable after approval.
 *
 * <p>{@code warnings} carries non-blocking notices, currently the "no needs assessment linked"
 * caution raised when a proposal is submitted without a survey or assessment report.
 */
public record ProgramResponse(
        String id,
        String title,
        String communityId,
        String communityName,
        String programTypeId,
        String programTypeName,
        String recommendationId,
        String surveyId,
        String surveyTitle,
        String objectives,
        Integer targetBeneficiaries,
        LocalDate proposedDate,
        LocalDate endDate,
        String venue,
        BigDecimal budgetRequested,
        BigDecimal budgetApproved,
        String facultyLeadId,
        String facultyLeadName,
        String status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<SectorResponse> sectors,
        List<ProgramDocumentResponse> documents,
        List<ProgramApprovalResponse> approvals,
        List<String> availableActions,
        boolean canEdit,
        boolean canRecordDelivery,
        List<String> warnings) {

    public static ProgramResponse fromEntity(Program program,
            String facultyLeadName,
            List<ProgramDocumentResponse> documents,
            List<ProgramApprovalResponse> approvals,
            List<String> availableActions,
            boolean canEdit,
            boolean canRecordDelivery,
            List<String> warnings) {
        return new ProgramResponse(
                program.getId(),
                program.getTitle(),
                program.getCommunity() == null ? null : program.getCommunity().getId(),
                program.getCommunity() == null ? null : program.getCommunity().getName(),
                program.getProgramType() == null ? null : program.getProgramType().getId(),
                program.getProgramType() == null ? null : program.getProgramType().getName(),
                program.getRecommendation() == null ? null : program.getRecommendation().getId(),
                program.getSurvey() == null ? null : program.getSurvey().getId(),
                program.getSurvey() == null ? null : program.getSurvey().getTitle(),
                program.getObjectives(),
                program.getTargetBeneficiaries(),
                program.getProposedDate(),
                program.getEndDate(),
                program.getVenue(),
                program.getBudgetRequested(),
                program.getBudgetApproved(),
                program.getFacultyLeadId(),
                facultyLeadName,
                program.getStatus(),
                program.getCreatedBy(),
                program.getCreatedAt(),
                program.getUpdatedAt(),
                program.getSectors().stream()
                        .map(SectorResponse::fromEntity)
                        .sorted(java.util.Comparator.comparing(SectorResponse::name))
                        .toList(),
                documents,
                approvals,
                availableActions,
                canEdit,
                canRecordDelivery,
                warnings);
    }
}
