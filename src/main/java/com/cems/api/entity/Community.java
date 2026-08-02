package com.cems.api.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Partner community master record (spec Module 2, §3.2). Captures GAD-ready population data
 * ({@code populationMale}/{@code populationFemale} — nullable when unknown), sector tags, and
 * uploaded documents. Soft-deleted via {@code deletedAt} (cross-cutting rule 3); never hard-deleted.
 */
@Entity
@Table(name = "communities")
public class Community {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    private String barangayCode;

    @Column(nullable = false)
    private String municipality;

    @Column(nullable = false)
    private String province;

    /** One of: urban_poor, rural, urban, coastal, other (enforced by DB check + service validation). */
    @Column(nullable = false)
    private String classification;

    private Integer estimatedPopulation;

    private Integer householdCount;

    private Integer populationMale;

    private Integer populationFemale;

    private String contactPersonName;

    private String contactPersonDesignation;

    private String contactPersonPhone;

    @Column(columnDefinition = "text")
    private String notes;

    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "community_sector",
            joinColumns = @JoinColumn(name = "community_id"),
            inverseJoinColumns = @JoinColumn(name = "sector_id")
    )
    private Set<Sector> sectors = new HashSet<>();

    @OneToMany(mappedBy = "community", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    private Set<CommunityDocument> documents = new LinkedHashSet<>();

    public Community() {
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

    public String getBarangayCode() {
        return barangayCode;
    }

    public void setBarangayCode(String barangayCode) {
        this.barangayCode = barangayCode;
    }

    public String getMunicipality() {
        return municipality;
    }

    public void setMunicipality(String municipality) {
        this.municipality = municipality;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public Integer getEstimatedPopulation() {
        return estimatedPopulation;
    }

    public void setEstimatedPopulation(Integer estimatedPopulation) {
        this.estimatedPopulation = estimatedPopulation;
    }

    public Integer getHouseholdCount() {
        return householdCount;
    }

    public void setHouseholdCount(Integer householdCount) {
        this.householdCount = householdCount;
    }

    public Integer getPopulationMale() {
        return populationMale;
    }

    public void setPopulationMale(Integer populationMale) {
        this.populationMale = populationMale;
    }

    public Integer getPopulationFemale() {
        return populationFemale;
    }

    public void setPopulationFemale(Integer populationFemale) {
        this.populationFemale = populationFemale;
    }

    public String getContactPersonName() {
        return contactPersonName;
    }

    public void setContactPersonName(String contactPersonName) {
        this.contactPersonName = contactPersonName;
    }

    public String getContactPersonDesignation() {
        return contactPersonDesignation;
    }

    public void setContactPersonDesignation(String contactPersonDesignation) {
        this.contactPersonDesignation = contactPersonDesignation;
    }

    public String getContactPersonPhone() {
        return contactPersonPhone;
    }

    public void setContactPersonPhone(String contactPersonPhone) {
        this.contactPersonPhone = contactPersonPhone;
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

    public Set<Sector> getSectors() {
        return sectors;
    }

    public void setSectors(Set<Sector> sectors) {
        this.sectors = sectors;
    }

    public Set<CommunityDocument> getDocuments() {
        return documents;
    }

    public void setDocuments(Set<CommunityDocument> documents) {
        this.documents = documents;
    }
}
