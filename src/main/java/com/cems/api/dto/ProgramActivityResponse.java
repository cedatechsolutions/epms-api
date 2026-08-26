package com.cems.api.dto;

import com.cems.api.entity.ProgramActivity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One activity row on the program's activities tab (spec Module 5b).
 *
 * <p>{@code attendance} is the running Total/F/M the spec requires on every activity, resolved in
 * the service from a single grouped query across the whole tab rather than per row.
 *
 * <p>{@code canRecordAttendance} is the server's answer to "should the quick-entry row render?" —
 * false for cancelled activities, which never happened (AC 4). As with {@code availableActions} on
 * a proposal, the client renders from this rather than re-deriving the rule.
 */
public record ProgramActivityResponse(
        String id,
        String programId,
        String title,
        LocalDate activityDate,
        LocalTime startTime,
        LocalTime endTime,
        String venue,
        String status,
        String notes,
        SexSplitResponse attendance,
        List<EvaluationResponse> evaluations,
        boolean canRecordAttendance,
        boolean canEdit,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static ProgramActivityResponse fromEntity(ProgramActivity activity,
            SexSplitResponse attendance,
            List<EvaluationResponse> evaluations,
            boolean canEdit) {
        return new ProgramActivityResponse(
                activity.getId(),
                activity.getProgram() == null ? null : activity.getProgram().getId(),
                activity.getTitle(),
                activity.getActivityDate(),
                activity.getStartTime(),
                activity.getEndTime(),
                activity.getVenue(),
                activity.getStatus(),
                activity.getNotes(),
                attendance,
                evaluations,
                activity.acceptsAttendance() && canEdit,
                canEdit,
                activity.getCreatedBy(),
                activity.getCreatedAt(),
                activity.getUpdatedAt());
    }
}
