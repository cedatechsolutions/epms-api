package com.cems.api.controller;

import com.cems.api.dto.RecommendationDecisionRequest;
import com.cems.api.dto.RecommendationResponse;
import com.cems.api.entity.Recommendation;
import com.cems.api.service.RecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recommendation engine endpoints (spec Module 4). Reads follow the assessment-view policy so
 * faculty can see what was recommended for their survey; generating and deciding are restricted to
 * the roles in the §2.2 matrix.
 */
@RestController
@RequestMapping("/api")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @PreAuthorize("@permissions.canDecideRecommendations()")
    @PostMapping("/surveys/{id}/recommendations/generate")
    public ResponseEntity<List<RecommendationResponse>> generate(@PathVariable String id) {
        return ResponseEntity.ok(recommendationService.generate(id));
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping("/surveys/{id}/recommendations")
    public ResponseEntity<List<RecommendationResponse>> list(@PathVariable String id) {
        return ResponseEntity.ok(recommendationService.list(id));
    }

    @PreAuthorize("@permissions.canDecideRecommendations()")
    @PostMapping("/recommendations/{id}/accept")
    public ResponseEntity<RecommendationResponse> accept(@PathVariable String id,
            @RequestBody(required = false) RecommendationDecisionRequest request) {
        return ResponseEntity.ok(recommendationService.decide(
                id, Recommendation.STATUS_ACCEPTED, noteOf(request)));
    }

    @PreAuthorize("@permissions.canDecideRecommendations()")
    @PostMapping("/recommendations/{id}/modify")
    public ResponseEntity<RecommendationResponse> modify(@PathVariable String id,
            @RequestBody(required = false) RecommendationDecisionRequest request) {
        return ResponseEntity.ok(recommendationService.decide(
                id, Recommendation.STATUS_MODIFIED, noteOf(request)));
    }

    @PreAuthorize("@permissions.canDecideRecommendations()")
    @PostMapping("/recommendations/{id}/reject")
    public ResponseEntity<RecommendationResponse> reject(@PathVariable String id,
            @RequestBody(required = false) RecommendationDecisionRequest request) {
        return ResponseEntity.ok(recommendationService.decide(
                id, Recommendation.STATUS_REJECTED, noteOf(request)));
    }

    private String noteOf(RecommendationDecisionRequest request) {
        return request == null ? null : request.note();
    }
}
