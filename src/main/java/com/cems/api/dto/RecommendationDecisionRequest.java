package com.cems.api.dto;

/**
 * Payload for accepting, modifying or rejecting a recommendation (spec Module 4 §3).
 *
 * <p>{@code note} is optional for accept/modify but <b>required for reject</b> — the spec calls for
 * a short reason. That rule is enforced in the service (422) rather than by a bean-validation
 * annotation, because the same payload serves all three decisions.
 */
public record RecommendationDecisionRequest(String note) {

    public static RecommendationDecisionRequest empty() {
        return new RecommendationDecisionRequest(null);
    }
}
