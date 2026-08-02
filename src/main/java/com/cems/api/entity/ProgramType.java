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
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * The admin-managed program-type library the recommendation engine matches against (spec §3.4).
 * Five types are seeded by Flyway migration {@code V8}; {@code active} retires a type from future
 * scoring runs without invalidating recommendations that already cite it.
 *
 * <p>Sectors are eagerly fetched because scoring needs them for every candidate type in one pass.
 */
@Entity
@Table(name = "program_types")
public class ProgramType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "default_duration")
    private String defaultDuration;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "program_type_sector",
            joinColumns = @JoinColumn(name = "program_type_id"),
            inverseJoinColumns = @JoinColumn(name = "sector_id")
    )
    private Set<Sector> sectors = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ProgramType() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultDuration() {
        return defaultDuration;
    }

    public void setDefaultDuration(String defaultDuration) {
        this.defaultDuration = defaultDuration;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Set<Sector> getSectors() {
        return sectors;
    }

    public void setSectors(Set<Sector> sectors) {
        this.sectors = sectors;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
