package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Assigns a person to a program (spec Module 5 §1). {@code roleInProgram} defaults to
 * {@code volunteer} when omitted.
 */
public record ProgramMemberRequest(@NotBlank String userId, String roleInProgram) {
}
