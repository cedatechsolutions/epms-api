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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One session of an approved program — a date, a venue, and the people who turned up
 * (spec Module 5b, §3.5).
 *
 * <p>Its {@code status} drives two program-level transitions, applied by
 * {@code ProgramActivityService} through {@code ProgramStateMachine}:
 * the first {@code done} activity moves the program {@code approved → ongoing}, and once every
 * activity is settled ({@code done} or {@code cancelled}) with at least one post-evaluation on
 * record the program becomes eligible for {@code completed}.
 *
 * <p>Soft-deleted via {@code deletedAt} (cross-cutting rule 3): attendance rows reference this
 * table, and they are the evidence the accomplishment report is built from.
 */
@Entity
@Table(name = "program_activities")
public class ProgramActivity {

    public static final String STATUS_SCHEDULED = "scheduled";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private LocalDate activityDate;

    private LocalTime startTime;

    private LocalTime endTime;

    private String venue;

    /** One of the three STATUS_* constants (enforced by DB check + service validation). */
    @Column(nullable = false)
    private String status = STATUS_SCHEDULED;

    @Column(columnDefinition = "text")
    private String notes;

    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;

    public ProgramActivity() {
    }

    /**
     * True when attendance may still be recorded against this session. A cancelled activity never
     * happened, so recording who attended it is a contradiction (spec Module 5 AC 4 → 409).
     */
    public boolean acceptsAttendance() {
        return !STATUS_CANCELLED.equals(status);
    }

    /** True when this activity no longer awaits delivery — it either happened or was called off. */
    public boolean isSettled() {
        return STATUS_DONE.equals(status) || STATUS_CANCELLED.equals(status);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Program getProgram() {
        return program;
    }

    public void setProgram(Program program) {
        this.program = program;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getActivityDate() {
        return activityDate;
    }

    public void setActivityDate(LocalDate activityDate) {
        this.activityDate = activityDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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
}
