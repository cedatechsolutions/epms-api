package com.cems.api.event;

import java.time.Instant;

/**
 * Published on the request thread when an auditable action occurs; consumed after the
 * surrounding transaction commits and persisted asynchronously as an {@code activity_logs}
 * row. All contextual data (actor, IP) is captured at publish time because the listener
 * runs on a different thread without request/security context.
 */
public record ActivityEvent(
        String userId,
        String action,
        String entityType,
        String entityId,
        String metadataJson,
        String ipAddress,
        Instant occurredAt) {
}
