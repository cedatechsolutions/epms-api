package com.cems.api.controller;

import com.cems.api.dto.ApiResponse;
import com.cems.api.dto.PublicSurveyResponse;
import com.cems.api.dto.SubmitResponseRequest;
import com.cems.api.service.PublicSurveyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated public survey endpoints (spec Module 3 §3). No auth, no PII exposure, no file
 * uploads. Submissions are rate-limited per IP by {@code PublicSurveyRateLimitFilter}.
 */
@RestController
@RequestMapping("/api/public/surveys")
public class PublicSurveyController {

    private final PublicSurveyService publicSurveyService;

    public PublicSurveyController(PublicSurveyService publicSurveyService) {
        this.publicSurveyService = publicSurveyService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<PublicSurveyResponse> getByToken(@PathVariable String token) {
        return ResponseEntity.ok(publicSurveyService.getByToken(token));
    }

    @PostMapping("/{token}/responses")
    public ResponseEntity<ApiResponse> submit(@PathVariable String token,
            @Valid @RequestBody SubmitResponseRequest request,
            HttpServletRequest servletRequest) {
        publicSurveyService.submit(token, request, clientIp(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Thank you. Your response has been recorded."));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
