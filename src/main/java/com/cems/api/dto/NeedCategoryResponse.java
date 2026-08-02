package com.cems.api.dto;

import com.cems.api.entity.NeedCategory;

/** A need category for tagging survey questions and grouping results (spec §3.3). */
public record NeedCategoryResponse(String id, String name, boolean active) {

    public static NeedCategoryResponse fromEntity(NeedCategory category) {
        return new NeedCategoryResponse(category.getId(), category.getName(), category.isActive());
    }
}
