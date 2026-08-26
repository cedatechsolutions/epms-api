package com.cems.api.dto;

import com.cems.api.entity.AttendanceRecord;

import java.time.Instant;

/**
 * One attendance row (spec Module 5b §4). This is personal data (RA 10173).
 *
 * <p><strong>{@code attendeeName} is null for masked readers.</strong> Student volunteers help run
 * activities and need the counts, not the roster — {@code AttendanceService} nulls the name for them
 * rather than the client hiding a value it was still sent. See
 * {@code Permissions.canViewBeneficiaryNames()} for the open question this defaults.
 */
public record AttendanceResponse(
        String id,
        String programActivityId,
        String attendeeName,
        String sex,
        Integer age,
        String sectorId,
        String sectorName,
        String communityId,
        String createdBy,
        Instant createdAt) {

    public static AttendanceResponse fromEntity(AttendanceRecord record, boolean includeName) {
        return new AttendanceResponse(
                record.getId(),
                record.getProgramActivity() == null ? null : record.getProgramActivity().getId(),
                includeName ? record.getAttendeeName() : null,
                record.getSex(),
                record.getAge(),
                record.getSector() == null ? null : record.getSector().getId(),
                record.getSector() == null ? null : record.getSector().getName(),
                record.getCommunity() == null ? null : record.getCommunity().getId(),
                record.getCreatedBy(),
                record.getCreatedAt());
    }
}
