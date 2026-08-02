package com.cems.api.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A needs-assessment survey targeted at one community (spec §3.3). Lifecycle:
 * {@code draft} (editable) → {@code deployed} (questions locked, public link live) → {@code closed}.
 * Soft-deleted via {@code deletedAt} (cross-cutting rule 3).
 */
@Entity
@Table(name = "surveys")
public class Survey {

    /** Survey lifecycle states (mirrors the DB check constraint). */
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_DEPLOYED = "deployed";
    public static final String STATUS_CLOSED = "closed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private String status = STATUS_DRAFT;

    @Column(name = "access_token")
    private String accessToken;

    private Instant opensAt;

    private Instant closesAt;

    private Integer targetResponses;

    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;

    @OneToMany(mappedBy = "survey", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<SurveyQuestion> questions = new ArrayList<>();

    public Survey() {
    }

    public boolean isDraft() {
        return STATUS_DRAFT.equals(status);
    }

    public boolean isDeployed() {
        return STATUS_DEPLOYED.equals(status);
    }

    public boolean isClosed() {
        return STATUS_CLOSED.equals(status);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Community getCommunity() {
        return community;
    }

    public void setCommunity(Community community) {
        this.community = community;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Instant getOpensAt() {
        return opensAt;
    }

    public void setOpensAt(Instant opensAt) {
        this.opensAt = opensAt;
    }

    public Instant getClosesAt() {
        return closesAt;
    }

    public void setClosesAt(Instant closesAt) {
        this.closesAt = closesAt;
    }

    public Integer getTargetResponses() {
        return targetResponses;
    }

    public void setTargetResponses(Integer targetResponses) {
        this.targetResponses = targetResponses;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public List<SurveyQuestion> getQuestions() {
        return questions;
    }

    public void setQuestions(List<SurveyQuestion> questions) {
        this.questions = questions;
    }
}
