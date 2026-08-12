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

import java.time.Instant;

/**
 * One immutable decision in a program's approval chain (spec §3.5, Module 5). Rows are appended,
 * never updated or deleted — a proposal that was returned twice keeps both returns, which is what
 * makes the audit trail defensible.
 *
 * <p>{@code stageRole} records which signatory acted. It is stored rather than derived because the
 * permission matrix binds each stage to exactly one role today, and a future reorganisation of that
 * chain must not rewrite what already happened.
 */
@Entity
@Table(name = "program_approvals")
public class ProgramApproval {

    public static final String ACTION_SUBMITTED = "submitted";
    public static final String ACTION_NOTED = "noted";
    public static final String ACTION_RECOMMENDED = "recommended";
    public static final String ACTION_APPROVED = "approved";
    public static final String ACTION_RETURNED = "returned";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    /** 1 = faculty submit, 2 = extension coordinator, 3 = campus extension coordinator, 4 = campus admin. */
    @Column(nullable = false)
    private int stage;

    /** Role code of the acting signatory (see {@code RoleName}). */
    @Column(nullable = false)
    private String stageRole;

    /** One of the ACTION_* constants. */
    @Column(nullable = false)
    private String action;

    private String actedBy;

    /** Required when {@code action} is {@code returned} (spec Module 5). */
    @Column(columnDefinition = "text")
    private String comment;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant actedAt;

    public ProgramApproval() {
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

    public int getStage() {
        return stage;
    }

    public void setStage(int stage) {
        this.stage = stage;
    }

    public String getStageRole() {
        return stageRole;
    }

    public void setStageRole(String stageRole) {
        this.stageRole = stageRole;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActedBy() {
        return actedBy;
    }

    public void setActedBy(String actedBy) {
        this.actedBy = actedBy;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Instant getActedAt() {
        return actedAt;
    }

    public void setActedAt(Instant actedAt) {
        this.actedAt = actedAt;
    }
}
