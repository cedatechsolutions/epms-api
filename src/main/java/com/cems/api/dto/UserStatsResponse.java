package com.cems.api.dto;

/** Summary counts for the user-management screen cards (excludes soft-deleted users). */
public record UserStatsResponse(long total, long active, long inactive) {
}
