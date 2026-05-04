package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.request.RoadmapGenerationRequest;
import com.careerplatform.career_assistant.dto.response.RoadmapResponse;
import com.careerplatform.career_assistant.dto.response.SpecializationResponse;
import com.careerplatform.career_assistant.service.RoadmapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/roadmap")
@RequiredArgsConstructor
public class RoadmapController {

    private final RoadmapService roadmapService;

    /**
     * POST /api/roadmap/generate
     * Generates personalized career roadmap from form data.
     * Saves form data to user profile first, then calls AI.
     * Archives existing active roadmap before saving new one.
     * Protected — requires JWT token.
     */
    @PostMapping("/generate")
    public ResponseEntity<RoadmapResponse> generateRoadmap(
            @Valid @RequestBody RoadmapGenerationRequest request) {
        log.info("Roadmap generation request received");
        return ResponseEntity.ok(roadmapService.generateRoadmap(request));
    }

    /**
     * GET /api/roadmap/my-roadmap
     * Returns current active roadmap for logged in user.
     * Throws 404 if no roadmap exists yet — frontend shows generate CTA.
     * Protected — requires JWT token.
     */
    @GetMapping("/my-roadmap")
    public ResponseEntity<RoadmapResponse> getMyRoadmap() {
        log.info("Active roadmap fetch request received");
        return ResponseEntity.ok(roadmapService.getMyRoadmap());
    }

    /**
     * GET /api/roadmap/history
     * Returns all roadmaps for logged in user — active and archived.
     * Used to show roadmap history page.
     * Protected — requires JWT token.
     */
    @GetMapping("/history")
    public ResponseEntity<List<RoadmapResponse>> getRoadmapHistory() {
        log.info("Roadmap history fetch request received");
        return ResponseEntity.ok(roadmapService.getRoadmapHistory());
    }

    /**
     * GET /api/roadmap/specializations
     * Returns AI-generated specialization options for a given dream job.
     * Called when user opens roadmap generation form.
     * Frontend renders options as selectable chips.
     * dreamJob passed as query param — taken from user's profile.
     * Protected — requires JWT token.
     */
    @GetMapping("/specializations")
    public ResponseEntity<SpecializationResponse> getSpecializations(
            @RequestParam String dreamJob) {
        log.info("Specialization options requested for: {}", dreamJob);
        return ResponseEntity.ok(roadmapService.getSpecializations(dreamJob));
    }
}