package com.cems.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The whole M&amp;E dashboard in one payload (spec Module 6: {@code GET /api/dashboard?period_id=} —
 * "returns all widgets in one payload", every aggregate computed in SQL).
 *
 * <p>{@code period} is null when the caller asked for every period at once; the client renders "All
 * periods" from that rather than inventing a label. Every person count is sex-disaggregated
 * (cross-cutting rule 1).
 *
 * <p>Nothing here is a percentage or a ratio. Completion rate, coverage against target and the like
 * are computed by the client from the raw pairs below, so the number on screen and the number in the
 * XLSX export are the same arithmetic performed once.
 */
public record MonitoringDashboardResponse(
        AcademicPeriodResponse period,
        Kpis kpis,
        List<TypeCount> programsByType,
        List<SectorCount> beneficiariesBySector,
        List<CompletionRow> completion,
        long completionTotal,
        Instant generatedAt) {

    /**
     * The four KPI cards (spec Module 6 §2).
     *
     * <p>{@code beneficiaries*} counts raw attendance rows — a person present at three sessions of
     * the same program counts three times. The spec permits this for v1 and requires the method be
     * stated on the card; {@link #beneficiaryMethod} carries that sentence so the wording lives with
     * the number instead of being retyped in the UI and again in each export.
     *
     * <p>{@code beneficiariesFemale + beneficiariesMale == beneficiariesTotal} always holds:
     * attendance sex is NOT NULL and CHECK-constrained to the two values (migration V11). There is
     * no undisclosed bucket to reconcile here, unlike survey responses.
     */
    public record Kpis(
            long communitiesServed,
            long programsTotal,
            long programsCompleted,
            long beneficiariesTotal,
            long beneficiariesFemale,
            long beneficiariesMale,
            long facultyInvolved,
            String beneficiaryMethod) {
    }

    /** One bar of the "programs by type" chart. */
    public record TypeCount(String programTypeId, String programTypeName, long programs) {
    }

    /**
     * One bar of the "beneficiaries by sector" chart. {@code sectorId} is null for the single
     * "Not specified" row that collects attendees recorded without a sector — they are real people
     * and are never dropped, only labelled honestly.
     */
    public record SectorCount(String sectorId, String sectorName, long total, long female, long male) {
    }

    /**
     * One row of the program completion table (spec Module 6 §4).
     *
     * <p>{@code targetBeneficiaries} is null on proposals that never set one, which is why it is not
     * folded into a percentage server-side: "0 of null" is not 0%, it is unknown, and only the client
     * can render that distinction.
     */
    public record CompletionRow(
            String programId,
            String title,
            String communityName,
            String programTypeName,
            String status,
            Integer targetBeneficiaries,
            long actualTotal,
            long actualFemale,
            long actualMale,
            boolean hasPreEvaluation,
            boolean hasPostEvaluation) {
    }
}
