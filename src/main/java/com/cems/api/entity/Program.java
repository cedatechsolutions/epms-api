package com.cems.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * An extension program / proposal (spec Module 5, §3.5) — the record that travels the four-stage
 * CvSU approval chain.
 *
 * <p><strong>Never set {@link #setStatus} outside {@code ProgramStateMachine}.</strong> Every legal
 * transition, and the role permitted to make it, is declared there; assigning the field directly
 * bypasses both the 409 illegal-transition guard and the {@code program_approvals} audit row.
 *
 * <p>{@code recommendation} and {@code survey} are nullable provenance links: a program may be born
 * from the recommendation engine (Module 4) or drafted by hand. Soft-deleted via {@code deletedAt}
 * (cross-cutting rule 3).
 */
@Entity
@Table(name = "programs")
public class Program {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_COORDINATOR_REVIEW = "coordinator_review";
    public static final String STATUS_RECOMMENDING_APPROVAL = "recommending_approval";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_RETURNED = "returned";
    public static final String STATUS_ONGOING = "ongoing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "community_id")
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_type_id")
    private ProgramType programType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id")
    private Recommendation recommendation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id")
    private Survey survey;

    @Column(columnDefinition = "text")
    private String objectives;

    private Integer targetBeneficiaries;

    private LocalDate proposedDate;

    private LocalDate endDate;

    private String venue;

    private BigDecimal budgetRequested;

    /** Set by the campus administrator at final approval; null until then. */
    private BigDecimal budgetApproved;

    private String facultyLeadId;

    /** One of the nine STATUS_* constants; owned exclusively by ProgramStateMachine. */
    @Column(nullable = false)
    private String status = STATUS_DRAFT;

    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "program_sector",
            joinColumns = @JoinColumn(name = "program_id"),
            inverseJoinColumns = @JoinColumn(name = "sector_id")
    )
    private Set<Sector> sectors = new HashSet<>();

    public Program() {
    }

    /**
     * True while the proposal is still the faculty's to edit. Everything from {@code submitted}
     * onward is read-only to them (spec Module 5 AC 2).
     */
    public boolean isEditableByOwner() {
        return STATUS_DRAFT.equals(status) || STATUS_RETURNED.equals(status);
    }

    /** True once the proposal has left the drafting stage and entered the approval chain. */
    public boolean isInApprovalChain() {
        return STATUS_SUBMITTED.equals(status)
                || STATUS_COORDINATOR_REVIEW.equals(status)
                || STATUS_RECOMMENDING_APPROVAL.equals(status);
    }

    /**
     * True when this program should block deletion of its community (spec Module 2 AC → 409).
     * Anything not cancelled counts, including drafts still being written.
     */
    public boolean isBlockingCommunityDeletion() {
        return !STATUS_CANCELLED.equals(status);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Community getCommunity() {
        return community;
    }

    public void setCommunity(Community community) {
        this.community = community;
    }

    public ProgramType getProgramType() {
        return programType;
    }

    public void setProgramType(ProgramType programType) {
        this.programType = programType;
    }

    public Recommendation getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(Recommendation recommendation) {
        this.recommendation = recommendation;
    }

    public Survey getSurvey() {
        return survey;
    }

    public void setSurvey(Survey survey) {
        this.survey = survey;
    }

    public String getObjectives() {
        return objectives;
    }

    public void setObjectives(String objectives) {
        this.objectives = objectives;
    }

    public Integer getTargetBeneficiaries() {
        return targetBeneficiaries;
    }

    public void setTargetBeneficiaries(Integer targetBeneficiaries) {
        this.targetBeneficiaries = targetBeneficiaries;
    }

    public LocalDate getProposedDate() {
        return proposedDate;
    }

    public void setProposedDate(LocalDate proposedDate) {
        this.proposedDate = proposedDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    public BigDecimal getBudgetRequested() {
        return budgetRequested;
    }

    public void setBudgetRequested(BigDecimal budgetRequested) {
        this.budgetRequested = budgetRequested;
    }

    public BigDecimal getBudgetApproved() {
        return budgetApproved;
    }

    public void setBudgetApproved(BigDecimal budgetApproved) {
        this.budgetApproved = budgetApproved;
    }

    public String getFacultyLeadId() {
        return facultyLeadId;
    }

    public void setFacultyLeadId(String facultyLeadId) {
        this.facultyLeadId = facultyLeadId;
    }

    public String getStatus() {
        return status;
    }

    /** @see ProgramStateMachine — do not call from anywhere else. */
    public void setStatus(String status) {
        this.status = status;
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

    public Set<Sector> getSectors() {
        return sectors;
    }

    public void setSectors(Set<Sector> sectors) {
        this.sectors = sectors;
    }
}
