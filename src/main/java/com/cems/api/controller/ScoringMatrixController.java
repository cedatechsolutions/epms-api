package com.cems.api.controller;

import com.cems.api.dto.ScoringMatrixRequest;
import com.cems.api.dto.ScoringMatrixResponse;
import com.cems.api.service.ProgramTypeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scoring matrix grid (spec Module 4 §4). Edits affect future scoring runs only — recommendations
 * already generated keep the breakdown they were produced with.
 */
@RestController
@RequestMapping("/api/scoring-matrix")
public class ScoringMatrixController {

    private final ProgramTypeService programTypeService;

    public ScoringMatrixController(ProgramTypeService programTypeService) {
        this.programTypeService = programTypeService;
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping
    public ResponseEntity<ScoringMatrixResponse> getMatrix() {
        return ResponseEntity.ok(programTypeService.getMatrix());
    }

    @PreAuthorize("@permissions.canConfigureScoringMatrix()")
    @PutMapping
    public ResponseEntity<ScoringMatrixResponse> updateMatrix(
            @Valid @RequestBody ScoringMatrixRequest request) {
        return ResponseEntity.ok(programTypeService.updateMatrix(request));
    }
}
