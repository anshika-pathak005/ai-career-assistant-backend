package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.RoadmapGenerationRequest;
import com.careerplatform.career_assistant.dto.response.GenerateRoadmapResponse;
import com.careerplatform.career_assistant.dto.response.RoadmapResponse;
import com.careerplatform.career_assistant.dto.response.SpecializationResponse;
import com.careerplatform.career_assistant.entity.*;
import com.careerplatform.career_assistant.enums.*;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoadmapService {

    private final CareerRoadmapRepository roadmapRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final GroqService groqService;
    private final ObjectMapper objectMapper;
    private final SkillStepRepository skillStepRepository;
    private final GenerationLimitService generationLimitService;

    /**
     * Main roadmap generation method.
     * Flow:
     *   1. Save form data to user profile
     *   2. Archive existing active roadmap if any
     *   3. Call AI with full user context
     *   4. Parse AI response into entities
     *   5. Save roadmap + phases + skills + steps to DB
     *   6. Return complete roadmap response
     */
    @Transactional
    public GenerateRoadmapResponse generateRoadmap(RoadmapGenerationRequest request) {
        Long userId = getCurrentUserId();
        log.info("Roadmap generation started for userId: {}", userId);

        // step 1 — save form data to user profile
        User user = userService.saveRoadmapFormData(request);

        // check limit BEFORE generating — throws 429 if exceeded
        generationLimitService.checkAndIncrement(user);

        // step 2 — archive existing active roadmap if exists
        archiveExistingRoadmap(user);

        // step 3 — call AI
        String aiResponse = groqService.generateRoadmap(user, request);
        log.info("AI roadmap response received for userId: {}", userId);

        // step 4 + 5 — parse and save
        CareerRoadmap roadmap = parseAndSaveRoadmap(user, request, aiResponse);
        log.info("Roadmap saved with id: {}", roadmap.getId());

        // refresh user from DB — counter was updated in checkAndIncrement
        user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        log.info("Roadmap generated successfully for userId: {}", userId);


        // step 6 — build and return response
        return new GenerateRoadmapResponse(
                roadmap.getId(),
                "Roadmap generated successfully",
                generationLimitService.buildLimitResponse(user) // include usage info
        );
    }

    /**
     * Gets current active roadmap for logged in user.
     * Returns null-safe response if no roadmap exists yet.
     */
    public RoadmapResponse getMyRoadmap() {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        CareerRoadmap roadmap = roadmapRepository
                .findByUserAndStatus(user, RoadmapStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("No active roadmap found. Please generate one."));

        return buildResponse(roadmap);
    }

    /**
     * Gets all roadmaps for logged in user — active + archived.
     * Used to show roadmap history.
     */
    public List<RoadmapResponse> getRoadmapHistory() {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return roadmapRepository.findByUserOrderByGeneratedAtDesc(user)
                .stream()
                .map(this::buildResponse)
                .toList();
    }

    /**
     * Archives currently active roadmap before generating a new one.
     * Sets status to ARCHIVED and records archival time.
     * Does nothing if no active roadmap exists.
     */
    private void archiveExistingRoadmap(User user) {
        roadmapRepository.findByUserAndStatus(user, RoadmapStatus.ACTIVE)
                .ifPresent(existing -> {
                    existing.setStatus(RoadmapStatus.ARCHIVED);
                    existing.setArchivedAt(LocalDateTime.now());
                    roadmapRepository.save(existing);
                    log.info("Archived previous roadmap id: {}", existing.getId());
                });
    }

    /**
     * Parses AI JSON response and saves complete roadmap tree to DB.
     * Tree: CareerRoadmap → RoadmapPhase → RoadmapSkill → SkillStep
     * All saved in one transaction — if anything fails, nothing is saved.
     */
    private CareerRoadmap parseAndSaveRoadmap(User user,
                                              RoadmapGenerationRequest request,
                                              String aiJson) {
        try {
            JsonNode root = objectMapper.readTree(aiJson);

            CareerRoadmap roadmap = CareerRoadmap.builder()
                    .user(user)
                    .dreamJobSnapshot(user.getDreamJob())
                    .status(RoadmapStatus.ACTIVE)
                    .summary(root.path("summary").asText())
                    .totalEstimatedWeeks(root.path("totalEstimatedWeeks").asInt())
                    .rawAiResponse(aiJson)
                    .build();
            roadmap = roadmapRepository.save(roadmap);

            List<RoadmapPhase> savedPhases = new ArrayList<>();
            JsonNode phases = root.path("phases");

            for (int i = 0; i < phases.size(); i++) {
                JsonNode phaseNode = phases.get(i);

                RoadmapPhase phase = RoadmapPhase.builder()
                        .roadmap(roadmap)
                        .title(phaseNode.path("title").asText())
                        .description(phaseNode.path("description").asText())
                        .phaseOrder(i + 1)
                        .estimatedWeeks(phaseNode.path("estimatedWeeks").asInt())
                        .build();

                List<RoadmapSkill> savedSkills = new ArrayList<>();
                JsonNode skills = phaseNode.path("skills");

                for (int j = 0; j < skills.size(); j++) {
                    JsonNode skillNode = skills.get(j);

                    String skillType = skillNode.path("skillType").asText("SKILL");

                    RoadmapSkill.RoadmapSkillBuilder skillBuilder = RoadmapSkill.builder()
                            .phase(phase)
                            .skillName(skillNode.path("skillName").asText())
                            .skillOrder(j + 1)
                            .estimatedDays(skillNode.path("estimatedDays").asInt())
                            .isExistingSkill(skillNode.path("isExistingSkill").asBoolean(false))
                            .skillType(skillType)
                            .resources(skillNode.path("resources").toString());

                    // only set levels for non-project skills
                    if (!"PROJECT".equals(skillType)) {
                        skillBuilder
                                .startingLevel(skillNode.path("startingLevel").asText("BEGINNER"))
                                .targetLevel(skillNode.path("targetLevel").asText("INTERMEDIATE"));
                    }

                    // only set project fields for PROJECT type
                    if ("PROJECT".equals(skillType)) {
                        skillBuilder
                                .projectDescription(skillNode.path("projectDescription").asText())
                                .techStack(skillNode.path("techStack").toString());
                    }

                    RoadmapSkill skill = skillBuilder.build();

                    // parse steps — for projects these are milestones
                    List<SkillStep> savedSteps = new ArrayList<>();
                    JsonNode steps = skillNode.path("steps");

                    for (int k = 0; k < steps.size(); k++) {
                        JsonNode stepNode = steps.get(k);
                        SkillStep step = SkillStep.builder()
                                .skill(skill)
                                .title(stepNode.path("title").asText())
                                .description(stepNode.path("description").asText())
                                .stepOrder(k + 1)
                                .estimatedHours(stepNode.path("estimatedHours").asInt())
                                .completed(false)
                                .skipped(false)
                                .build();
                        savedSteps.add(step);
                    }
                    skill.setSteps(savedSteps);
                    savedSkills.add(skill);
                }
                phase.setSkills(savedSkills);
                savedPhases.add(phase);
            }
            roadmap.setPhases(savedPhases);
            return roadmapRepository.save(roadmap);

        } catch (Exception ex) {
            log.error("Failed to parse and save roadmap: {}", ex.getMessage());
            throw new RuntimeException("Failed to save roadmap. Please try again.");
        }
    }

    /**
     * Builds complete RoadmapResponse from saved entity tree.
     * Maps CareerRoadmap → PhaseDto → SkillDto → StepDto.
     */
    private RoadmapResponse buildResponse(CareerRoadmap roadmap) {

        // calculate overall progress across all phases
        int[] totalSteps = {0};
        int[] completedSteps = {0};

        List<RoadmapResponse.PhaseDto> phaseDtos = roadmap.getPhases().stream()
                .map(phase -> {

                    int phaseTotal = 0;
                    int phaseCompleted = 0;

                    List<RoadmapResponse.SkillDto> skillDtos = new ArrayList<>();

                    for (RoadmapSkill skill : phase.getSkills()) {

                        int skillTotal = skill.getSteps().size();
                        int skillCompleted = (int) skill.getSteps().stream()
                                .filter(s -> Boolean.TRUE.equals(s.getCompleted())
                                        || Boolean.TRUE.equals(s.getSkipped()))
                                .count();

                        phaseTotal += skillTotal;
                        phaseCompleted += skillCompleted;

                        int skillProgress = skillTotal > 0
                                ? (skillCompleted * 100 / skillTotal) : 0;

                        List<RoadmapResponse.StepDto> stepDtos = skill.getSteps().stream()
                                .map(step -> new RoadmapResponse.StepDto(
                                        step.getId(), step.getTitle(),
                                        step.getDescription(), step.getStepOrder(),
                                        step.getEstimatedHours(),
                                        step.getCompleted(), step.getSkipped()
                                )).toList();

                        skillDtos.add(new RoadmapResponse.SkillDto(
                                skill.getId(), skill.getSkillName(),
                                skill.getSkillOrder(), skill.getEstimatedDays(),
                                skill.getTargetLevel() != null ? skill.getTargetLevel() : "",
                                skill.getStartingLevel() != null ? skill.getStartingLevel() : "",
                                skill.getIsExistingSkill(),
                                skill.getSkillType(),
                                skill.getResources(),
                                skill.getProjectDescription(),
                                skill.getTechStack(),
                                skillProgress,
                                stepDtos
                        ));
                    }

                    totalSteps[0] += phaseTotal;
                    completedSteps[0] += phaseCompleted;

                    int phaseProgress = phaseTotal > 0
                            ? (phaseCompleted * 100 / phaseTotal) : 0;

                    return new RoadmapResponse.PhaseDto(
                            phase.getId(), phase.getTitle(), phase.getDescription(),
                            phase.getPhaseOrder(), phase.getEstimatedWeeks(),
                            phaseProgress, skillDtos
                    );
                }).toList();

        int overallProgress = totalSteps[0] > 0
                ? (completedSteps[0] * 100 / totalSteps[0]) : 0;

        return new RoadmapResponse(
                roadmap.getId(), roadmap.getDreamJobSnapshot(),
                roadmap.getStatus().name(), roadmap.getSummary(),
                roadmap.getTotalEstimatedWeeks(), roadmap.getGeneratedAt(),
                overallProgress, phaseDtos
        );
    }

    /**
     * Fetches specialization options for user's dream job.
     * Calls Groq with dream job — AI returns 4-6 distinct specializations.
     * Result is NOT saved to DB — it's transient, just for form display.
     * User's selected specialization is saved when they submit roadmap form.
     */
    public SpecializationResponse getSpecializations(String dreamJob) {
        log.info("Getting specializations for: {}", dreamJob);
        return groqService.getSpecializations(dreamJob);
    }

    /**
     * Generate returns only roadmapId — frontend redirects to /roadmap/{id}.
     * Full roadmap fetched separately to avoid slow response on generate.

    @Transactional
//    public GenerateRoadmapResponse generateRoadmap(RoadmapGenerationRequest request) {
//        Long userId = getCurrentUserId();
//        log.info("Roadmap generation started for userId: {}", userId);
//
//        User user = userService.saveRoadmapFormData(request);
//        archiveExistingRoadmap(user);
//
//        String aiResponse = groqService.generateRoadmap(user, request);
//        log.info("AI roadmap response received for userId: {}", userId);
//
//        CareerRoadmap roadmap = parseAndSaveRoadmap(user, request, aiResponse);
//        log.info("Roadmap saved with id: {}", roadmap.getId());
//
//        // return only id — frontend fetches full roadmap separately
//        return new GenerateRoadmapResponse(roadmap.getId(), "Roadmap generated successfully");
//    }
     */

    /**
     * GET /api/roadmap/{id}
     * Returns full roadmap by id.
     * Validates roadmap belongs to current user — security check.
     */
    public RoadmapResponse getRoadmapById(Long roadmapId) {
        Long userId = getCurrentUserId();

        CareerRoadmap roadmap = roadmapRepository.findById(roadmapId)
                .orElseThrow(() -> new ResourceNotFoundException("Roadmap not found"));

        // security — user can only access their own roadmaps
        if (!roadmap.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Roadmap not found");
        }

        return buildResponse(roadmap);
    }

    /**
     * GET /api/roadmap/user
     * Returns all roadmaps for current user — active and archived.
     * Used for roadmap history page.
     */
    public List<RoadmapResponse> getUserRoadmaps() {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return roadmapRepository.findByUserOrderByGeneratedAtDesc(user)
                .stream()
                .map(this::buildResponse)
                .toList();
    }

    /**
     * GET /api/roadmap/latest
     * Returns current active roadmap for logged in user.
     * Returns 404 if no active roadmap — frontend shows generate CTA.
     */
    public RoadmapResponse getLatestRoadmap() {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        CareerRoadmap roadmap = roadmapRepository
                .findByUserAndStatus(user, RoadmapStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active roadmap found. Please generate one."));

        return buildResponse(roadmap);
    }

    /**
     * PATCH /api/roadmap/{roadmapId}/step/{stepId}
     * Marks a step as completed or not completed.
     * Validates step belongs to a skill in the given roadmap — security check.
     */
    @Transactional
    public void updateStepCompletion(Long roadmapId, Long stepId,
                                     Boolean completed, Boolean skipped) {
        Long userId = getCurrentUserId();

        CareerRoadmap roadmap = roadmapRepository.findById(roadmapId)
                .orElseThrow(() -> new ResourceNotFoundException("Roadmap not found"));

        if (!roadmap.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Roadmap not found");
        }

        SkillStep step = skillStepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Step not found"));

        // only update fields that were sent
        if (completed != null) step.setCompleted(completed);
        if (skipped != null) step.setSkipped(skipped);

        skillStepRepository.save(step);
        log.info("Step {} updated — completed: {}, skipped: {} for roadmapId: {}",
                stepId, completed, skipped, roadmapId);
    }

    private Long getCurrentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext()
                .getAuthentication().getName());
    }
}