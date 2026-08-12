package com.cems.api.dto;

/**
 * Payload for the three approval-chain endpoints (spec Module 5 API surface):
 * {@code /review {action: note|return}}, {@code /recommend {action: recommend|return}},
 * {@code /approve {action: approve|return}}.
 *
 * <p>{@code comment} is optional when advancing but <b>required when returning</b> — enforced in
 * {@code ProgramStateMachine} (422) rather than by annotation, since one payload serves both the
 * advance and the return branch of each endpoint.
 *
 * <p>{@code budgetApproved} is honoured only by {@code /approve}: the campus administrator may
 * approve a different figure than was requested. Ignored elsewhere.
 */
public record ProgramStageActionRequest(String action, String comment, java.math.BigDecimal budgetApproved) {

    /** True when the caller asked to send the proposal back rather than advance it. */
    public boolean isReturn() {
        return action != null && "return".equalsIgnoreCase(action.trim());
    }
}
