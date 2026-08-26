package com.cems.api.controller;

import com.cems.api.dto.ApiResponse;
import com.cems.api.dto.AttendanceImportResponse;
import com.cems.api.dto.AttendanceRequest;
import com.cems.api.dto.AttendanceResponse;
import com.cems.api.dto.EvaluationRequest;
import com.cems.api.dto.EvaluationResponse;
import com.cems.api.dto.ProgramActivityRequest;
import com.cems.api.dto.ProgramActivityResponse;
import com.cems.api.dto.SexSplitResponse;
import com.cems.api.service.AttendanceService;
import com.cems.api.service.EvaluationService;
import com.cems.api.service.EvaluationService.FileDownload;
import com.cems.api.service.ProgramActivityService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Activities, attendance and evaluations (spec Module 5b API surface).
 *
 * <p>Routes follow the spec exactly: activities are created under their program
 * ({@code POST /api/programs/{id}/activities}) but addressed directly once they exist
 * ({@code /api/activities/{id}}), because an activity id is globally unique and the client already
 * holds it.
 *
 * <p>{@code @PreAuthorize} here is only the coarse role gate. The per-program rules — you must own
 * the program, and it must be approved or ongoing — live in {@code ProgramAccessPolicy}, which
 * distinguishes 403 (not yours) from 409 (not yet, or already finished).
 */
@RestController
public class ProgramActivityController {

    private final ProgramActivityService activityService;
    private final AttendanceService attendanceService;
    private final EvaluationService evaluationService;

    public ProgramActivityController(ProgramActivityService activityService,
            AttendanceService attendanceService,
            EvaluationService evaluationService) {
        this.activityService = activityService;
        this.attendanceService = attendanceService;
        this.evaluationService = evaluationService;
    }

    // --- activities ---

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/api/programs/{programId}/activities")
    public ResponseEntity<List<ProgramActivityResponse>> list(@PathVariable String programId) {
        return ResponseEntity.ok(activityService.listForProgram(programId));
    }

    /** The program's running Total/F/M across every activity (spec AC 4). */
    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/api/programs/{programId}/attendance-totals")
    public ResponseEntity<SexSplitResponse> programTotals(@PathVariable String programId) {
        return ResponseEntity.ok(activityService.attendanceTotalsForProgram(programId));
    }

    @PreAuthorize("@permissions.canRecordDelivery()")
    @PostMapping("/api/programs/{programId}/activities")
    public ResponseEntity<ProgramActivityResponse> create(@PathVariable String programId,
            @Valid @RequestBody ProgramActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(activityService.create(programId, request));
    }

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/api/activities/{activityId}")
    public ResponseEntity<ProgramActivityResponse> get(@PathVariable String activityId) {
        return ResponseEntity.ok(activityService.getById(activityId));
    }

    @PreAuthorize("@permissions.canRecordDelivery()")
    @PatchMapping("/api/activities/{activityId}")
    public ResponseEntity<ProgramActivityResponse> update(@PathVariable String activityId,
            @Valid @RequestBody ProgramActivityRequest request) {
        return ResponseEntity.ok(activityService.update(activityId, request));
    }

    @PreAuthorize("@permissions.canRecordDelivery()")
    @DeleteMapping("/api/activities/{activityId}")
    public ResponseEntity<ApiResponse> delete(@PathVariable String activityId) {
        activityService.delete(activityId);
        return ResponseEntity.ok(new ApiResponse("Activity deleted."));
    }

    // --- attendance ---

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/api/activities/{activityId}/attendance")
    public ResponseEntity<List<AttendanceResponse>> listAttendance(@PathVariable String activityId) {
        return ResponseEntity.ok(attendanceService.list(activityId));
    }

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/api/activities/{activityId}/attendance/totals")
    public ResponseEntity<SexSplitResponse> attendanceTotals(@PathVariable String activityId) {
        return ResponseEntity.ok(attendanceService.totals(activityId));
    }

    @PreAuthorize("@permissions.canRecordDelivery()")
    @PostMapping("/api/activities/{activityId}/attendance")
    public ResponseEntity<AttendanceResponse> addAttendance(@PathVariable String activityId,
            @Valid @RequestBody AttendanceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attendanceService.add(activityId, request));
    }

    /**
     * CSV import (columns: name, sex, age, sector). Returns 200 even when rows were rejected — the
     * import is partial by design, and the body carries the row-level report (spec AC 4).
     */
    @PreAuthorize("@permissions.canRecordDelivery()")
    @PostMapping("/api/activities/{activityId}/attendance/import")
    public ResponseEntity<AttendanceImportResponse> importAttendance(@PathVariable String activityId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(attendanceService.importCsv(activityId, file));
    }

    @PreAuthorize("@permissions.canRecordDelivery()")
    @DeleteMapping("/api/activities/{activityId}/attendance/{attendanceId}")
    public ResponseEntity<ApiResponse> deleteAttendance(@PathVariable String activityId,
            @PathVariable String attendanceId) {
        attendanceService.delete(activityId, attendanceId);
        return ResponseEntity.ok(new ApiResponse("Attendance record removed."));
    }

    // --- evaluations ---

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/api/activities/{activityId}/evaluations")
    public ResponseEntity<List<EvaluationResponse>> listEvaluations(@PathVariable String activityId) {
        return ResponseEntity.ok(evaluationService.list(activityId));
    }

    @PreAuthorize("@permissions.canRecordDelivery()")
    @PostMapping("/api/activities/{activityId}/evaluations")
    public ResponseEntity<EvaluationResponse> createEvaluation(@PathVariable String activityId,
            @Valid @RequestBody EvaluationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evaluationService.create(activityId, request));
    }

    @PreAuthorize("@permissions.canRecordDelivery()")
    @PatchMapping("/api/activities/{activityId}/evaluations/{evaluationId}")
    public ResponseEntity<EvaluationResponse> updateEvaluation(@PathVariable String activityId,
            @PathVariable String evaluationId,
            @Valid @RequestBody EvaluationRequest request) {
        return ResponseEntity.ok(evaluationService.update(activityId, evaluationId, request));
    }

    @PreAuthorize("@permissions.canRecordDelivery()")
    @DeleteMapping("/api/activities/{activityId}/evaluations/{evaluationId}")
    public ResponseEntity<ApiResponse> deleteEvaluation(@PathVariable String activityId,
            @PathVariable String evaluationId) {
        evaluationService.delete(activityId, evaluationId);
        return ResponseEntity.ok(new ApiResponse("Evaluation deleted."));
    }

    @PreAuthorize("@permissions.canRecordDelivery()")
    @PostMapping(value = "/api/activities/{activityId}/evaluations/{evaluationId}/file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EvaluationResponse> attachEvaluationFile(@PathVariable String activityId,
            @PathVariable String evaluationId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(evaluationService.attachFile(activityId, evaluationId, file));
    }

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/api/activities/{activityId}/evaluations/{evaluationId}/file")
    public ResponseEntity<Resource> downloadEvaluationFile(@PathVariable String activityId,
            @PathVariable String evaluationId) {
        FileDownload download = evaluationService.loadFile(activityId, evaluationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.filename() + "\"")
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.sizeBytes())
                .body(download.resource());
    }
}
