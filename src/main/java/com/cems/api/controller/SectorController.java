package com.cems.api.controller;

import com.cems.api.dto.SectorResponse;
import com.cems.api.service.SectorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sectors")
public class SectorController {

    private final SectorService sectorService;

    public SectorController(SectorService sectorService) {
        this.sectorService = sectorService;
    }

    @PreAuthorize("@permissions.canViewCommunities()")
    @GetMapping
    public ResponseEntity<List<SectorResponse>> listSectors() {
        return ResponseEntity.ok(sectorService.listActive());
    }
}
