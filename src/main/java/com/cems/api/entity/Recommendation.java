package com.cems.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One scored program-type suggestion for a finalized assessment (spec §3.4).
 *
 * <p>{@code scoreBreakdown} holds the JSON explanation of how {@code matchScore} was reached — the
 * spec requires every displayed score to be reproducible from it, so it is written on every
 * generation and never recomputed afterwards. Re-running the engine replaces {@code pending} rows
 * only: a decided recommendation is part of the audit trail (Module 4 AC 1).
 */
@Entity
@Table(name = "recommendations")
public class Recommendation {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_MODIFIED = "modified";
    public static final String STATUS_REJECTED = "rejected";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "program_type_id", nullable = false)
    private ProgramType programType;

    /** 0–100, normalized against this program type's own theoretical maximum. */
    @Column(name = "match_score", nullable = false)
    private BigDecimal matchScore;

    @Column(name = "score_breakdown", columnDefinition = "text")
    private String scoreBreakdown;

    @Column(name = "rank_position", nullable = false)
    private int rankPosition;

    @Column(nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Recommendation() {
    }

    /** True once a coordinator has ruled on this row, which makes it survive a regeneration. */
    public boolean isDecided() {
        return !STATUS_PENDING.equals(status);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Survey getSurvey() {
        return survey;
    }

    public void setSurvey(Survey survey) {
        this.survey = survey;
    }

    public ProgramType getProgramType() {
        return programType;
    }

    public void setProgramType(ProgramType programType) {
        this.programType = programType;
    }

    public BigDecimal getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(BigDecimal matchScore) {
        this.matchScore = matchScore;
    }

    public String getScoreBreakdown() {
        return scoreBreakdown;
    }

    public void setScoreBreakdown(String scoreBreakdown) {
        this.scoreBreakdown = scoreBreakdown;
    }

    public int getRankPosition() {
        return rankPosition;
    }

    public void setRankPosition(int rankPosition) {
        this.rankPosition = rankPosition;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
