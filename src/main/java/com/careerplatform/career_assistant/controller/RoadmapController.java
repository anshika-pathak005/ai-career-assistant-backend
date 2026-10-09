package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.request.RoadmapGenerationRequest;
import com.careerplatform.career_assistant.dto.request.StepCompletionRequest;
import com.careerplatform.career_assistant.dto.response.GenerateRoadmapResponse;
import com.careerplatform.career_assistant.dto.response.RoadmapResponse;
import com.careerplatform.career_assistant.dto.response.SpecializationResponse;
import com.careerplatform.career_assistant.ratelimit.RateLimit;
import com.careerplatform.career_assistant.ratelimit.RateLimitTier;
import com.careerplatform.career_assistant.security.CurrentUserId;
import com.careerplatform.career_assistant.service.RoadmapService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/roadmap")
@RequiredArgsConstructor
public class RoadmapController {

    private final RoadmapService roadmapService;

    /**
     * POST /api/v1/roadmap/generate
     * Saves form data to the profile, then calls the AI and archives any active roadmap.
     */
    // Groq call at ~7000 max tokens, the most expensive endpoint in the app.
    @RateLimit(tier = RateLimitTier.AI, cost = 7)
    @PostMapping("/generate")
    public ResponseEntity<GenerateRoadmapResponse> generateRoadmap(
            @CurrentUserId Long userId,
            @Valid @RequestBody RoadmapGenerationRequest request) {
        log.info("Roadmap generation request for userId: {}", userId);
        return ResponseEntity.ok(roadmapService.generateRoadmap(userId, request));
    }

    /**
     * GET /api/v1/roadmap/my-roadmap
     * The active roadmap. 404 if none exists yet — the frontend shows a generate CTA.
     */
    @GetMapping("/my-roadmap")
    public ResponseEntity<RoadmapResponse> getMyRoadmap(@CurrentUserId Long userId) {
        log.info("Active roadmap fetch for userId: {}", userId);
        return ResponseEntity.ok(roadmapService.getMyRoadmap(userId));
    }

    /**
     * GET /api/v1/roadmap/history
     * Every roadmap for the user, active and archived.
     */
    @GetMapping("/history")
    public ResponseEntity<List<RoadmapResponse>> getRoadmapHistory(@CurrentUserId Long userId) {
        log.info("Roadmap history fetch for userId: {}", userId);
        return ResponseEntity.ok(roadmapService.getRoadmapHistory(userId));
    }

    /**
     * GET /api/v1/roadmap/specializations
     * AI-generated specialization options for a dream job, rendered as selectable chips.
     *
     * Declared BEFORE /{id}: Spring prefers a literal path over a variable one, so the
     * order does not strictly matter here, but keeping literals above the catch-all makes
     * it obvious that "specializations" is not meant to be parsed as an id.
     */
    // Small Groq call, ~800 tokens.
    @RateLimit(tier = RateLimitTier.AI, cost = 1)
    @GetMapping("/specializations")
    public ResponseEntity<SpecializationResponse> getSpecializations(
            /*
             * dreamJob goes straight into a Groq prompt, so it is capped and checked
             * against a character allowlist - otherwise this endpoint is an uncapped
             * LLM proxy running on your API key.
             *
             * An allowlist rather than a blocklist: real job titles are letters, spaces
             * and a little punctuation, and enumerating bad input never ends.
             */
            @RequestParam
            @NotBlank(message = "Dream job is required")
            @Size(max = 100, message = "Dream job must be 100 characters or fewer")
            @Pattern(regexp = "^[A-Za-z0-9 .,'/&+-]+$",
                     message = "Dream job contains unsupported characters")
            String dreamJob) {
        log.info("Specialization options requested for: {}", dreamJob);
        return ResponseEntity.ok(roadmapService.getSpecializations(dreamJob));
    }

    /**
     * GET /api/v1/roadmap/{id}
     * Full roadmap by id. Ownership is enforced in the query, not by a follow-up check.
     */
    @GetMapping("/{id}")
    public ResponseEntity<RoadmapResponse> getRoadmapById(
            @CurrentUserId Long userId,
            @PathVariable Long id) {
        log.info("Roadmap fetch request for id: {} by userId: {}", id, userId);
        return ResponseEntity.ok(roadmapService.getRoadmapById(userId, id));
    }

    /**
     * PATCH /api/v1/roadmap/{roadmapId}/step/{stepId}
     * Marks a step completed or skipped. Body: { "completed": true, "skipped": false }
     */
    @PatchMapping("/{roadmapId}/step/{stepId}")
    public ResponseEntity<Void> updateStepCompletion(
            @CurrentUserId Long userId,
            @PathVariable Long roadmapId,
            @PathVariable Long stepId,
            @Valid @RequestBody StepCompletionRequest request) {
        log.info("Step completion update for roadmapId: {}, stepId: {}, userId: {}",
                roadmapId, stepId, userId);
        roadmapService.updateStepCompletion(
                userId, roadmapId, stepId, request.getCompleted(), request.getSkipped());
        return ResponseEntity.noContent().build();
    }
}
