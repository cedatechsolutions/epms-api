package com.cems.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Seeded lookup of GAD target sectors (spec §3.2): Youth, Women, 4Ps, MSMEs, Senior Citizens,
 * PWD, Farmers/Fisherfolk, OSY. Rows are created by Flyway migration {@code V4}; {@code isActive}
 * lets an admin retire a sector without deleting historical tags.
 */
@Entity
@Table(name = "sectors")
public class Sector {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public Sector() {
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
