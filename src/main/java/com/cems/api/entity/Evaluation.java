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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An encoded pre- or post-activity evaluation summary (spec Module 5b, §3.5).
 *
 * <p>This is a <strong>summary, not per-respondent rows</strong>: the spec asks for counts and an
 * average rating, and the filled instruments stay on paper — optionally scanned and attached via
 * {@code filePath}.
 *
 * <p>{@code femaleCount} and {@code maleCount} are stored rather than derived from attendance.
 * Evaluation respondents are not the same population as attendees — someone present may not have
 * answered — so recomputing them from {@link AttendanceRecord} would report a number that was never
 * collected. Their sum may be less than {@code respondentCount} when respondents declined to state
 * a sex; it may never exceed it (DB check).
 *
 * <p>At least one {@code post} evaluation on a program is a precondition for {@code completed}
 * (spec Module 5 AC 5).
 */
@Entity
@Table(name = "evaluations")
public class Evaluation {

    public static final String TYPE_PRE = "pre";
    public static final String TYPE_POST = "post";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_activity_id", nullable = false)
    private ProgramActivity programActivity;

    /** {@code pre} or {@code post} (enforced by DB check + service validation). */
    @Column(nullable = false)
    private String evalType;

    @Column(nullable = false)
    private int respondentCount;

    @Column(nullable = false)
    private int femaleCount;

    @Column(nullable = false)
    private int maleCount;

    /** 1.00–5.00, or null when the instrument carried no rating scale. */
    private BigDecimal avgRating;

    @Column(columnDefinition = "text")
    private String notes;

    /** Storage-relative path of the optional scanned instrument; never exposed to clients. */
    private String filePath;

    private String originalFilename;

    private String mimeType;

    private Long sizeBytes;

    private String encodedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public Evaluation() {
    }

    /** Respondents counted in the total but in neither sex bucket (declined to state). */
    public int getUnspecifiedCount() {
        return Math.max(0, respondentCount - femaleCount - maleCount);
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

    public String getEvalType() {
        return evalType;
    }

    public void setEvalType(String evalType) {
        this.evalType = evalType;
    }

    public int getRespondentCount() {
        return respondentCount;
    }

    public void setRespondentCount(int respondentCount) {
        this.respondentCount = respondentCount;
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

    public BigDecimal getAvgRating() {
        return avgRating;
    }

    public void setAvgRating(BigDecimal avgRating) {
        this.avgRating = avgRating;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getEncodedBy() {
        return encodedBy;
    }

    public void setEncodedBy(String encodedBy) {
        this.encodedBy = encodedBy;
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
}
