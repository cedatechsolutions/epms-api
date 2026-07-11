package com.cems.api.controller;

import com.cems.api.dto.ActivityLogQuery;
import com.cems.api.dto.ActivityLogResponse;
import com.cems.api.dto.PaginatedResponse;
import com.cems.api.service.ActivityLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activity-logs")
public class ActivityLogController {

    private final ActivityLogService activityLogService;

    public ActivityLogController(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @PreAuthorize("@permissions.canViewActivityLogs()")
    @GetMapping
    public ResponseEntity<PaginatedResponse<ActivityLogResponse>> list(ActivityLogQuery query,
            HttpServletRequest request) {
        return ResponseEntity.ok(new PaginatedResponse<>(
                activityLogService.search(query),
                request.getRequestURI()));
    }
}
