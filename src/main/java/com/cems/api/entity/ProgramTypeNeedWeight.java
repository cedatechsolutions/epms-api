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

import java.math.BigDecimal;

/**
 * One cell of the scoring matrix: how strongly a program type addresses a need category, 0.00–5.00
 * (spec §3.4). A missing row means weight 0 — the type does not address that need at all, so the
 * matrix is stored sparsely and the grid endpoint fills the gaps.
 */
@Entity
@Table(name = "program_type_need_weights")
public class ProgramTypeNeedWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_type_id", nullable = false)
    private ProgramType programType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "need_category_id", nullable = false)
    private NeedCategory needCategory;

    @Column(nullable = false)
    private BigDecimal weight = BigDecimal.ZERO;

    public ProgramTypeNeedWeight() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ProgramType getProgramType() {
        return programType;
    }

    public void setProgramType(ProgramType programType) {
        this.programType = programType;
    }

    public NeedCategory getNeedCategory() {
        return needCategory;
    }

    public void setNeedCategory(NeedCategory needCategory) {
        this.needCategory = needCategory;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }
}
