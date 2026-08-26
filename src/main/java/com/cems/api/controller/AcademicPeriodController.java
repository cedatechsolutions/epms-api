package com.cems.api.controller;

import com.cems.api.dto.AcademicPeriodResponse;
import com.cems.api.service.AcademicPeriodService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The semester lookup behind the dashboard's period selector and the programs list's period filter
 * (spec Module 6 API surface: {@code GET /api/academic-periods}). Read-only — periods are seeded.
 */
@RestController
@RequestMapping("/api/academic-periods")
public class AcademicPeriodController {

    private final AcademicPeriodService academicPeriodService;

    public AcademicPeriodController(AcademicPeriodService academicPeriodService) {
        this.academicPeriodService = academicPeriodService;
    }

    @PreAuthorize("@permissions.canViewAcademicPeriods()")
    @GetMapping
    public ResponseEntity<List<AcademicPeriodResponse>> list() {
        return ResponseEntity.ok(academicPeriodService.list());
    }
}
