package com.cems.api.controller;

import com.cems.api.dto.NeedCategoryResponse;
import com.cems.api.service.NeedCategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/need-categories")
public class NeedCategoryController {

    private final NeedCategoryService needCategoryService;

    public NeedCategoryController(NeedCategoryService needCategoryService) {
        this.needCategoryService = needCategoryService;
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping
    public ResponseEntity<List<NeedCategoryResponse>> listCategories() {
        return ResponseEntity.ok(needCategoryService.listActive());
    }
}
