package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.RoadmapGenerationRequest;
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
    public RoadmapResponse generateRoadmap(RoadmapGenerationRequest request) {
        Long userId = getCurrentUserId();
        log.info("Roadmap generation started for userId: {}", userId);

        // step 1 — save form data to user profile
        User user = userService.saveRoadmapFormData(request);

        // step 2 — archive existing active roadmap if exists
        archiveExistingRoadmap(user);

        // step 3 — call AI
        String aiResponse = groqService.generateRoadmap(user, request);
        log.info("AI roadmap response received for userId: {}", userId);

        // step 4 + 5 — parse and save
        CareerRoadmap roadmap = parseAndSaveRoadmap(user, request, aiResponse);
        log.info("Roadmap saved with id: {}", roadmap.getId());

        // step 6 — build and return response
        return buildResponse(roadmap);
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
    private CareerRoadmap parseAndSaveRoadmap(User user, RoadmapGenerationRequest request, String aiJson) {
        try {
            JsonNode root = objectMapper.readTree(aiJson);

            // save root roadmap
            CareerRoadmap roadmap = CareerRoadmap.builder()
                    .user(user)
                    .dreamJobSnapshot(user.getDreamJob())
                    .status(RoadmapStatus.ACTIVE)
                    .summary(root.path("summary").asText())
                    .totalEstimatedWeeks(root.path("totalEstimatedWeeks").asInt())
                    .rawAiResponse(aiJson)
                    .build();
            roadmap = roadmapRepository.save(roadmap);

            // save phases
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

                // save skills inside phase
                List<RoadmapSkill> savedSkills = new ArrayList<>();
                JsonNode skills = phaseNode.path("skills");

                for (int j = 0; j < skills.size(); j++) {
                    JsonNode skillNode = skills.get(j);

                    String skillName = skillNode.path("skillName").asText();
                    String startingLevelStr = skillNode.path("startingLevel").asText("BEGINNER");
                    String targetLevelStr = skillNode.path("targetLevel").asText("INTERMEDIATE");

                    RoadmapSkill skill = RoadmapSkill.builder()
                            .phase(phase)
                            .skillName(skillName)
                            .skillOrder(j + 1)
                            .estimatedDays(skillNode.path("estimatedDays").asInt())
                            .startingLevel(SkillLevel.valueOf(startingLevelStr.toUpperCase()))
                            .targetLevel(SkillLevel.valueOf(targetLevelStr.toUpperCase()))
                            .isExistingSkill(skillNode.path("isExistingSkill").asBoolean(false))
                            .resources(skillNode.path("resources").toString())
                            .build();

                    // save steps inside skill
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
        List<RoadmapResponse.PhaseDto> phaseDtos = roadmap.getPhases().stream()
                .map(phase -> {
                    List<RoadmapResponse.SkillDto> skillDtos = phase.getSkills().stream()
                            .map(skill -> {
                                List<RoadmapResponse.StepDto> stepDtos = skill.getSteps().stream()
                                        .map(step -> new RoadmapResponse.StepDto(
                                                step.getId(), step.getTitle(),
                                                step.getDescription(), step.getStepOrder(),
                                                step.getEstimatedHours(), step.getCompleted()
                                        )).toList();

                                return new RoadmapResponse.SkillDto(
                                        skill.getId(), skill.getSkillName(),
                                        skill.getSkillOrder(), skill.getEstimatedDays(),
                                        skill.getTargetLevel().name(),
                                        skill.getStartingLevel().name(),
                                        skill.getIsExistingSkill(),
                                        skill.getResources(), stepDtos
                                );
                            }).toList();

                    return new RoadmapResponse.PhaseDto(
                            phase.getId(), phase.getTitle(), phase.getDescription(),
                            phase.getPhaseOrder(), phase.getEstimatedWeeks(), skillDtos
                    );
                }).toList();

        return new RoadmapResponse(
                roadmap.getId(), roadmap.getDreamJobSnapshot(),
                roadmap.getStatus().name(), roadmap.getSummary(),
                roadmap.getTotalEstimatedWeeks(), roadmap.getGeneratedAt(),
                phaseDtos
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

    private Long getCurrentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext()
                .getAuthentication().getName());
    }
}