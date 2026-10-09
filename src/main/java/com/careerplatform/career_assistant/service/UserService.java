package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.RoadmapGenerationRequest;
import com.careerplatform.career_assistant.dto.request.UpdateProfileRequest;
import com.careerplatform.career_assistant.dto.response.GenerationLimitResponse;
import com.careerplatform.career_assistant.dto.response.UserProfileResponse;
import com.careerplatform.career_assistant.entity.*;
import com.careerplatform.career_assistant.enums.RoadmapStatus;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.AnalysisResultRepository;
import com.careerplatform.career_assistant.repository.CareerRoadmapRepository;
import com.careerplatform.career_assistant.repository.ResumeRepository;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.config.properties.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /*
     * 6.1: userId is a parameter now, resolved once in the controller by
     * @CurrentUserId. It used to come from a private getCurrentUserId() copy-pasted into
     * this and four other services, which parsed it out of the SecurityContext principal
     * name - NPE when unauthenticated, NumberFormatException if the principal was not
     * numeric, both surfacing as 500s. Taking it as an argument also means these methods
     * can be unit tested without a SecurityContext.
     */

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final GenerationLimitService generationLimitService;
    private final CareerRoadmapRepository roadmapRepository;
    private final ResumeRepository resumeRepository;
    private final AnalysisResultRepository analysisResultRepository;

    /** 7.1: only for turning a pending deletion timestamp into the date it executes. */
    private final AppProperties appProperties;

    /**
     * Fetches complete profile of currently logged in user.
     * userId extracted from Spring SecurityContext — set by JwtAuthenticationFilter.
     * Returns all fields including new roadmap preference fields.
     * Used to pre-fill roadmap generation form on frontend.
     */
    // One transaction for the whole method, so the lazy roadmap tree resolves
    // inside an open session. readOnly = true: no dirty-checking, no flush.
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(Long userId) {
        log.info("Fetching profile for userId: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return mapToResponse(user);
    }

    /**
     * Updates user profile from profile settings page.
     * Partial update pattern — only non-null fields are updated in DB.
     * Null field = user did not send it = keep existing DB value.
     * skillLevels Map is converted to JSON string before saving.
     * Does not trigger roadmap generation — pure profile update.
     */
    // Writes, then calls mapToResponse() which walks the roadmap tree. Not readOnly.
    @Transactional
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        log.info("Updating profile for userId: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // only update fields that were actually sent
        if (request.getCurrentRole() != null) user.setCurrentRole(request.getCurrentRole());
        if (request.getExperienceYears() != null) user.setExperienceYears(request.getExperienceYears());
        if (request.getIndustries() != null) user.setIndustries(request.getIndustries());
        if (request.getSkills() != null) user.setSkills(request.getSkills());
        if (request.getGoalType() != null) user.setGoalType(request.getGoalType());
        if (request.getProjectLevel() != null) user.setProjectLevel(request.getProjectLevel());
        if (request.getWorkPreference() != null) user.setWorkPreference(request.getWorkPreference());
        if (request.getWeeklyHours() != null) user.setWeeklyHours(request.getWeeklyHours());
        if (request.getLearningStyle() != null) user.setLearningStyle(request.getLearningStyle());
        if (request.getSkillLevels() != null) {
            try {
                // convert Map to JSON string for storage
                user.setSkillLevels(objectMapper.writeValueAsString(request.getSkillLevels()));
            } catch (JsonProcessingException ex) {
                log.error("Failed to serialize skillLevels: {}", ex.getMessage());
            }
        }

        User updated = userRepository.save(user);
        log.info("Profile updated for userId: {}", userId);
        return mapToResponse(updated);
    }

    /**
     * Saves roadmap generation form data to user profile before generating roadmap.
     * Called internally by RoadmapService before AI call — not a direct API.
     * Updates both editable profile fields (currentRole, skills etc.)
     * and new preference fields (goalType, weeklyHours, learningStyle etc.)
     * Ensures DB always has latest data user submitted in roadmap form.
     * Returns updated User entity — passed directly to AI prompt builder.
     */
    public User saveRoadmapFormData(Long userId, RoadmapGenerationRequest request) {
        log.info("Saving roadmap form data for userId: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // update editable profile fields if user changed them
        if (request.getCurrentRole() != null) user.setCurrentRole(request.getCurrentRole());
        if (request.getExperienceYears() != null) user.setExperienceYears(Integer.valueOf(request.getExperienceYears()));
        if (request.getIndustries() != null) user.setIndustries(request.getIndustries());
        if (request.getSkills() != null) user.setSkills(request.getSkills());

        // save new preference fields
        if (request.getGoalType() != null) user.setGoalType(request.getGoalType());
        if (request.getProjectLevel() != null) user.setProjectLevel(request.getProjectLevel());
        if (request.getWorkPreference() != null) user.setWorkPreference(request.getWorkPreference());
        if (request.getWeeklyHours() != null) user.setWeeklyHours(request.getWeeklyHours());
        if (request.getSkillLevels() != null) {
            try {
                user.setSkillLevels(objectMapper.writeValueAsString(request.getSkillLevels()));
            } catch (JsonProcessingException ex) {
                log.error("Failed to serialize skillLevels: {}", ex.getMessage());
            }
        }

        User saved = userRepository.save(user);
        log.info("Roadmap form data saved for userId: {}", userId);
        return saved;
    }

    /**
     * Maps User entity to UserProfileResponse DTO.
     * Internal helper — keeps mapToResponse logic in one place.
     * Called by both getUserProfile() and updateProfile().
     */
    private UserProfileResponse mapToResponse(User user) {

        // get active roadmap summary
        UserProfileResponse.RoadmapSummaryDto roadmapSummary = null;
        Optional<CareerRoadmap> activeRoadmap = roadmapRepository
                .findByUserAndStatus(user, RoadmapStatus.ACTIVE);

        if (activeRoadmap.isPresent()) {
            CareerRoadmap r = activeRoadmap.get();

            // calculate overall progress
            int total = 0, completed = 0;
            for (RoadmapPhase phase : r.getPhases()) {
                for (RoadmapSkill skill : phase.getSkills()) {
                    for (SkillStep step : skill.getSteps()) {
                        total++;
                        if (Boolean.TRUE.equals(step.getCompleted())
                                || Boolean.TRUE.equals(step.getSkipped())) completed++;
                    }
                }
            }
            int progress = total > 0 ? (completed * 100 / total) : 0;

            roadmapSummary = new UserProfileResponse.RoadmapSummaryDto(
                    r.getId(), r.getDreamJobSnapshot(),
                    r.getStatus().name(), progress,
                    r.getTotalEstimatedWeeks(), r.getGeneratedAt()
            );
        }

        // get latest resume summary
        UserProfileResponse.ResumeSummaryDto resumeSummary = null;
        List<Resume> resumes = resumeRepository.findByUserOrderByUploadTimeDesc(user);

        if (!resumes.isEmpty()) {
            Resume latest = resumes.get(0);
            Optional<AnalysisResult> analysis = analysisResultRepository.findByResume(latest);

            resumeSummary = analysis.map(a -> new UserProfileResponse.ResumeSummaryDto(
                    latest.getId(), a.getId(), latest.getFileName(),
                    a.getOverallScore(), a.getAtsScore(),
                    a.getInterviewReadiness(), latest.getUploadTime()
            )).orElse(new UserProfileResponse.ResumeSummaryDto(
                    latest.getId(), null, latest.getFileName(),
                    null, null, null, latest.getUploadTime()
            ));
        }

        return new UserProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getUserCategory(),
                user.getDreamJob(),
                user.getIndustries(),
                user.getSkills(),
                user.getExperienceYears(),
                user.getCurrentRole(),
                user.getGoalType(),
                user.getProjectLevel(),
                user.getWorkPreference(),
                user.getWeeklyHours(),
                user.getLearningStyle(),
                user.getSkillLevels(),
                user.getWizardStep(),
                user.getIsProfileComplete(),
                generationLimitService.buildLimitResponse(user),      // roadmap usage
                generationLimitService.buildResumeLimitResponse(user), // resume usage
                roadmapSummary,
                resumeSummary,
                appProperties.account().purgeTimeFor(user.getDeletionRequestedAt())
        );
    }

    /**
     * Returns current generation usage for logged in user.
     * Used by dashboard to show "2/3 roadmaps used this month".
     */
    public GenerationLimitResponse getUsage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return generationLimitService.buildLimitResponse(user);
    }
}