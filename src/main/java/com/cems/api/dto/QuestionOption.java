package com.cems.api.dto;

/** A single choice option for multiple_choice/checkbox questions (spec §3.3 options JSON). */
public record QuestionOption(String label, String value) {
}
