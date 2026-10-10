package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.RoadmapGenerationRequest;
import com.careerplatform.career_assistant.dto.response.GenerateRoadmapResponse;
import com.careerplatform.career_assistant.dto.response.RoadmapResponse;
import com.careerplatform.career_assistant.dto.response.SpecializationResponse;
import com.careerplatform.career_assistant.entity.*;
import com.careerplatform.career_assistant.enums.*;
import com.careerplatform.career_assistant.exception.IncompleteProfileException;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.careerplatform.career_assistant.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;

    /**
     * Main roadmap generation method.
     * Flow:
     *   1. Save form data to user profile, consume quota, archive old roadmap  [TX 1]
     *   2. Call AI with full user context                                      [no TX]
     *   3. Parse AI response and save the roadmap tree                         [TX 2]
     *
     * NOT @Transactional — deliberately.
     *
     * A Spring transaction holds a JDBC connection for its whole duration, and the Groq
     * call can take up to a minute. With HikariCP's default pool of 10, wrapping this
     * method would let ten concurrent generations hold every connection in the pool and
     * block every other request in the app — login, dashboard, health check — behind
     * connection acquisition.
     *
     * So the slow call sits between two short transactions, and a connection is only
     * held while the database is actually being used.
     *
     * TransactionTemplate rather than private @Transactional methods, because Spring
     * proxies the bean: a self-invoked method never passes through the proxy, so its
     * @Transactional would be silently ignored.
     *
     * The HTTP thread is still parked for the duration — making this asynchronous is
     * what fixes that.
     */
    public GenerateRoadmapResponse generateRoadmap(Long userId, RoadmapGenerationRequest request) {
        log.info("Roadmap generation started for userId: {}", userId);

        // ── TX 1: short, DB only ──────────────────────────────────────────────
        // Validate profile completeness, save form data, consume quota, archive the previous roadmap.
        // Pre-validation runs before quota is consumed so incomplete accounts are rejected
        // without deducting from their monthly allowance.
        User user = transactionTemplate.execute(status -> {
            User existingUser = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));

            if (!Boolean.TRUE.equals(existingUser.getIsProfileComplete())
                    || existingUser.getDreamJob() == null
                    || existingUser.getDreamJob().isBlank()) {
                throw new IncompleteProfileException(
                        "Please complete your profile setup (including your dream job) before generating a career roadmap.");
            }

            // Quota first, for two reasons. consume() runs a bulk UPDATE with
            // clearAutomatically, which detaches everything already in the persistence
            // context; and there is no point writing form data for a request that is
            // about to be rejected.
            generationLimitService.consume(userId, GenerationLimitService.LimitType.ROADMAP);

            User u = userService.saveRoadmapFormData(userId, request);
            archiveExistingRoadmap(u);
            return u;
        });

        // ── No transaction: the slow part ─────────────────────────────────────
        // `user` is detached here. Everything the prompt reads (dreamJob,
        // userCategory, currentRole, industries) is a basic column or a JSON
        // column, not a lazy association, so this does not need an open session.
        String aiResponse;
        try {
            aiResponse = groqService.generateRoadmap(user, request);
        } catch (RuntimeException ex) {
            // The AI call failed after quota was already consumed. Refund it —
            // the user should not be charged for a generation they never got.
            generationLimitService.release(userId, GenerationLimitService.LimitType.ROADMAP);
            throw ex;
        }
        log.info("AI roadmap response received for userId: {}", userId);

        // ── TX 2: short, DB only ──────────────────────────────────────────────
        // Parse the response and persist the roadmap tree.
        return transactionTemplate.execute(status -> {
            User fresh = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));

            CareerRoadmap roadmap = parseAndSaveRoadmap(fresh, request, aiResponse);
            log.info("Roadmap saved with id: {}", roadmap.getId());

            return new GenerateRoadmapResponse(
                    roadmap.getId(),
                    "Roadmap generated successfully",
                    generationLimitService.buildLimitResponse(fresh) // include usage info
            );
        });
    }

    /**
     * Gets current active roadmap for logged in user.
     * Returns null-safe response if no roadmap exists yet.
     */
    // One transaction for the whole method, so the lazy roadmap tree resolves
    // inside an open session. readOnly = true: no dirty-checking, no flush.
    @Transactional(readOnly = true)
    public RoadmapResponse getMyRoadmap(Long userId) {
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
    // One transaction for the whole method, so the lazy roadmap tree resolves
    // inside an open session. readOnly = true: no dirty-checking, no flush.
    @Transactional(readOnly = true)
    public List<RoadmapResponse> getRoadmapHistory(Long userId) {
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
    /**
     * Cached: this is a pure function of a job title, and the same few titles are
     * requested constantly, so every hit is a Groq call saved.
     *
     * Keyed on the normalised dreamJob, which is safe because the controller caps it at
     * 100 characters and checks it against a character allowlist.
     */
    @Cacheable(cacheNames = CacheConfig.SPECIALIZATIONS, key = "#dreamJob.trim().toLowerCase()")
    public SpecializationResponse getSpecializations(String dreamJob) {
        log.info("Getting specializations for: {}", dreamJob);
        return groqService.getSpecializations(dreamJob);
    }

    /**
     * GET /api/v1/roadmap/{id}
     * Returns full roadmap by id.
     * Validates roadmap belongs to current user — security check.
     */
    // One transaction for the whole method, so the lazy roadmap tree resolves
    // inside an open session. readOnly = true: no dirty-checking, no flush.
    @Transactional(readOnly = true)
    public RoadmapResponse getRoadmapById(Long userId, Long roadmapId) {

        // SECURITY: ownership is enforced in the query, not by a follow-up check.
        CareerRoadmap roadmap = roadmapRepository.findByIdAndUserId(roadmapId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Roadmap not found"));

        return buildResponse(roadmap);
    }

    /**
     * PATCH /api/v1/roadmap/{roadmapId}/step/{stepId}
     * Marks a step as completed or not completed.
     * Validates step belongs to a skill in the given roadmap — security check.
     */
    @Transactional
    public void updateStepCompletion(Long userId, Long roadmapId, Long stepId,
                                     Boolean completed, Boolean skipped) {

        // SECURITY: a single query enforces BOTH conditions —
        //   1. the step actually lives inside this roadmap
        //   2. this roadmap belongs to the caller
        // Both must stay in the query. Checking ownership in a separate if is what lets
        // someone pass their own roadmapId with another user's stepId.
        // Returns 404 rather than 403 — a 403 would confirm the step exists.
        SkillStep step = skillStepRepository.findOwnedStep(stepId, roadmapId, userId)
                .orElseThrow(() -> {
                    log.warn("Denied step update — userId: {}, roadmapId: {}, stepId: {}",
                            userId, roadmapId, stepId);
                    return new ResourceNotFoundException("Step not found");
                });

        // only update fields that were sent
        if (completed != null) step.setCompleted(completed);
        if (skipped != null) step.setSkipped(skipped);

        skillStepRepository.save(step);
        log.info("Step {} updated — completed: {}, skipped: {} for roadmapId: {}",
                stepId, completed, skipped, roadmapId);
    }

}