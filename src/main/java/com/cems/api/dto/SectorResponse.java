package com.cems.api.dto;

import com.cems.api.entity.Sector;

/** A GAD target sector for tagging communities (spec §3.2). */
public record SectorResponse(String id, String name, boolean active) {

    public static SectorResponse fromEntity(Sector sector) {
        return new SectorResponse(sector.getId(), sector.getName(), sector.isActive());
    }
}
