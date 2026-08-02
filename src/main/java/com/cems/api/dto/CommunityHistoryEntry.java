package com.cems.api.dto;

import java.time.Instant;

/**
 * One entry in a community's extension history (spec Module 2 §2). Derived from programs,
 * which arrive in Phase 4 — the history endpoint returns an empty list until then.
 */
public record CommunityHistoryEntry(
        String programId,
        String programTitle,
        String status,
        Instant date,
        int beneficiaryCount) {
}
