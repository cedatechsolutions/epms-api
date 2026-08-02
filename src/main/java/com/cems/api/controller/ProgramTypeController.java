package com.cems.api.controller;

import com.cems.api.dto.ProgramTypeRequest;
import com.cems.api.dto.ProgramTypeResponse;
import com.cems.api.service.ProgramTypeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The program-type library (spec Module 4 §4). Anyone who can view assessments may read the library
 * — recommendation cards name these types — but only matrix configurers may change it.
 */
@RestController
@RequestMapping("/api/program-types")
public class ProgramTypeController {

    private final ProgramTypeService programTypeService;

    public ProgramTypeController(ProgramTypeService programTypeService) {
        this.programTypeService = programTypeService;
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping
    public ResponseEntity<List<ProgramTypeResponse>> list() {
        return ResponseEntity.ok(programTypeService.list());
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping("/{id}")
    public ResponseEntity<ProgramTypeResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(programTypeService.getById(id));
    }

    @PreAuthorize("@permissions.canConfigureScoringMatrix()")
    @PostMapping
    public ResponseEntity<ProgramTypeResponse> create(@Valid @RequestBody ProgramTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(programTypeService.create(request));
    }

    @PreAuthorize("@permissions.canConfigureScoringMatrix()")
    @PatchMapping("/{id}")
    public ResponseEntity<ProgramTypeResponse> update(@PathVariable String id,
            @Valid @RequestBody ProgramTypeRequest request) {
        return ResponseEntity.ok(programTypeService.update(id, request));
    }
}
