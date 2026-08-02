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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable snapshot of one need category's score, taken when results are finalized (spec §3.3).
 * Live results are computed on demand by {@code AssessmentScoringService}; this table freezes them
 * so recommendations (Phase 3) and the QF-23 report always cite the same numbers.
 */
@Entity
@Table(name = "assessment_results")
public class AssessmentResult {

    public static final String PRIORITY_CRITICAL = "critical";
    public static final String PRIORITY_HIGH = "high";
    public static final String PRIORITY_MODERATE = "moderate";
    public static final String PRIORITY_LOW = "low";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "need_category_id", nullable = false)
    private NeedCategory needCategory;

    @Column(name = "avg_score", nullable = false)
    private BigDecimal avgScore;

    @Column(name = "response_count", nullable = false)
    private int responseCount;

    /** GAD disaggregation — respondents in this category, by sex. */
    @Column(name = "female_count", nullable = false)
    private int femaleCount;

    @Column(name = "male_count", nullable = false)
    private int maleCount;

    @Column(nullable = false)
    private String priority;

    @Column(name = "rank_position", nullable = false)
    private int rankPosition;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    public AssessmentResult() {
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

    public NeedCategory getNeedCategory() {
        return needCategory;
    }

    public void setNeedCategory(NeedCategory needCategory) {
        this.needCategory = needCategory;
    }

    public BigDecimal getAvgScore() {
        return avgScore;
    }

    public void setAvgScore(BigDecimal avgScore) {
        this.avgScore = avgScore;
    }

    public int getResponseCount() {
        return responseCount;
    }

    public void setResponseCount(int responseCount) {
        this.responseCount = responseCount;
    }

    public int getFemaleCount() {
        return femaleCount;
    }

    public void setFemaleCount(int femaleCount) {
        this.femaleCount = femaleCount;
    }

    public int getMaleCount() {
        return maleCount;
    }

    public void setMaleCount(int maleCount) {
        this.maleCount = maleCount;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public int getRankPosition() {
        return rankPosition;
    }

    public void setRankPosition(int rankPosition) {
        this.rankPosition = rankPosition;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }
}
