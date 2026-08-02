package com.cems.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Seeded lookup of need categories (spec §3.3): Health, Livelihood, Education, Environment,
 * Safety, Governance, General. Rows are created by Flyway migration {@code V5}; {@code isActive}
 * lets an admin retire a category without breaking historical questions/results.
 */
@Entity
@Table(name = "need_categories")
public class NeedCategory {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public NeedCategory() {
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
