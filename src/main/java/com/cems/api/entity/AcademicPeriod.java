package com.cems.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A semester or midyear term (spec Module 6 §1). Read-only lookup: rows are seeded by migration
 * {@code V12} and there is no write endpoint.
 *
 * <p>Programs are not joined to this table — they are matched to it by {@code proposed_date} falling
 * inside {@link #getStartsOn()}..{@link #getEndsOn()}. See the V12 migration header for why the
 * mapping is by date rather than by foreign key.
 */
@Entity
@Table(name = "academic_periods")
public class AcademicPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String label;

    @Column(nullable = false)
    private LocalDate startsOn;

    @Column(nullable = false)
    private LocalDate endsOn;

    /**
     * Seeded hint only. Ask {@code AcademicPeriodService.resolveCurrent()} which period is current —
     * it reads the date range first and falls back to this flag only for dates that fall in a gap
     * between terms.
     */
    @Column(nullable = false)
    private boolean isCurrent;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public AcademicPeriod() {
    }

    /** True when {@code date} falls inside this term, inclusive of both endpoints. */
    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public void setStartsOn(LocalDate startsOn) {
        this.startsOn = startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public void setEndsOn(LocalDate endsOn) {
        this.endsOn = endsOn;
    }

    public boolean isCurrent() {
        return isCurrent;
    }

    public void setCurrent(boolean current) {
        this.isCurrent = current;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
