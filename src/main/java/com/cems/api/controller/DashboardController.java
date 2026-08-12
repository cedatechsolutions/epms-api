package com.cems.api.controller;

import com.cems.api.dto.DashboardOverviewResponse;
import com.cems.api.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The landing dashboard's single aggregate read (plan Phase 6 §BE: "one payload, all aggregates in
 * SQL"). Every authenticated role gets the same endpoint; the payload narrows itself — proposal
 * counts are scoped to what the caller may see, and the activity feed is omitted for non-admins.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @PreAuthorize("@permissions.canViewDashboard()")
    @GetMapping("/overview")
    public ResponseEntity<DashboardOverviewResponse> overview() {
        return ResponseEntity.ok(dashboardService.getOverview());
    }
}
