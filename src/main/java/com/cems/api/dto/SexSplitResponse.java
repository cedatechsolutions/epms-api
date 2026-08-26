package com.cems.api.dto;

/**
 * A head count with its GAD disaggregation (cross-cutting rule 1: every count surface shows
 * Total/F/M).
 *
 * <p>{@code total} is carried explicitly rather than derived from {@code female + male}. For
 * attendance the two agree by construction — {@code sex} is NOT NULL there — but for evaluation
 * summaries a respondent may decline to state a sex, so the split can legitimately sum to less than
 * the total. Clients render that remainder in words rather than inventing a third bucket.
 */
public record SexSplitResponse(long total, long female, long male) {

    public static final SexSplitResponse EMPTY = new SexSplitResponse(0, 0, 0);

    /** Respondents counted in the total but in neither bucket. */
    public long unspecified() {
        return Math.max(0, total - female - male);
    }
}
