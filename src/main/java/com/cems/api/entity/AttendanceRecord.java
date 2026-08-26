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
 * One beneficiary present at one activity (spec Module 5b, §3.5).
 *
 * <p><strong>{@code sex} is required and constrained to {@code female}/{@code male}.</strong> The
 * GAD disaggregation every count surface in this system must show is computed from this column, and
 * a nullable value would silently create a third bucket no report knows how to render. CSV rows
 * carrying anything else are rejected per-row rather than coerced.
 *
 * <p>{@code community} is denormalized from the parent program at insert time so beneficiary
 * exports can group by community without a two-table walk — and so that a program later retargeted
 * to a different community does not retroactively rewrite who was served where.
 *
 * <p>This is personal data (RA 10173): reads are permission-gated and exports are written to
 * {@code activity_logs}.
 */
@Entity
@Table(name = "attendance_records")
public class AttendanceRecord {

    public static final String SEX_FEMALE = "female";
    public static final String SEX_MALE = "male";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_activity_id", nullable = false)
    private ProgramActivity programActivity;

    @Column(nullable = false)
    private String attendeeName;

    /** {@code female} or {@code male} — never null (enforced by DB check + service validation). */
    @Column(nullable = false)
    private String sex;

    private Integer age;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sector_id")
    private Sector sector;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "community_id")
    private Community community;

    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public AttendanceRecord() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ProgramActivity getProgramActivity() {
        return programActivity;
    }

    public void setProgramActivity(ProgramActivity programActivity) {
        this.programActivity = programActivity;
    }

    public String getAttendeeName() {
        return attendeeName;
    }

    public void setAttendeeName(String attendeeName) {
        this.attendeeName = attendeeName;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public Sector getSector() {
        return sector;
    }

    public void setSector(Sector sector) {
        this.sector = sector;
    }

    public Community getCommunity() {
        return community;
    }

    public void setCommunity(Community community) {
        this.community = community;
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
}
