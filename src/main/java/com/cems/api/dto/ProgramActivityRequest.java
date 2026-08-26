package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Create/update payload for one session of a program (spec Module 5b). The same shape serves both.
 *
 * <p>{@code status} is accepted here because an activity's status is ordinary data the faculty lead
 * edits directly — unlike {@code programs.status}, which only {@code ProgramStateMachine} may
 * assign. Setting it to {@code done} is what advances the parent program to {@code ongoing}, so the
 * service routes that side effect through the state machine rather than writing it here.
 */
public record ProgramActivityRequest(
        @NotBlank String title,
        @NotNull LocalDate activityDate,
        LocalTime startTime,
        LocalTime endTime,
        String venue,
        String status,
        String notes) {
}
