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
 * Someone assigned to a program besides its faculty lead — a student volunteer helping run the
 * sessions, or a co-faculty member (spec Module 5 §1, §2.2).
 *
 * <p><strong>Membership grants visibility, not authority.</strong> Being on this table lets a
 * restricted role find and read the program; it does not let them edit the proposal or record
 * attendance. Those still require being the creator or lead, or holding a coordinator role — see
 * {@code ProgramAccessPolicy}. Conflating the two would hand a volunteer write access to the
 * beneficiary record simply for having been assigned to help.
 */
@Entity
@Table(name = "program_members")
public class ProgramMember {

    public static final String ROLE_VOLUNTEER = "volunteer";
    public static final String ROLE_CO_FACULTY = "co_faculty";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String roleInProgram = ROLE_VOLUNTEER;

    private String assignedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public ProgramMember() {
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRoleInProgram() {
        return roleInProgram;
    }

    public void setRoleInProgram(String roleInProgram) {
        this.roleInProgram = roleInProgram;
    }

    public String getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(String assignedBy) {
        this.assignedBy = assignedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
