package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.RoadmapGenerationRequest;
import com.careerplatform.career_assistant.dto.request.UpdateProfileRequest;
import com.careerplatform.career_assistant.dto.response.UserProfileResponse;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Fetches complete profile of currently logged in user.
     * userId extracted from Spring SecurityContext — set by JwtAuthenticationFilter.
     * Returns all fields including new roadmap preference fields.
     * Used to pre-fill roadmap generation form on frontend.
     */
    public UserProfileResponse getUserProfile() {
        Long userId = getCurrentUserId();
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
    public UserProfileResponse updateProfile(UpdateProfileRequest request) {
        Long userId = getCurrentUserId();
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
    public User saveRoadmapFormData(RoadmapGenerationRequest request) {
        Long userId = getCurrentUserId();
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
//                user.getLearningStyle(),
                user.getSkillLevels(),
                user.getWizardStep(),
                user.getIsProfileComplete()
        );
    }

    /**
     * Extracts userId of currently logged in user from Spring SecurityContext.
     * SecurityContext is populated by JwtAuthenticationFilter on every request.
     * Returns Long userId — used to fetch user from DB.
     */

    private Long getCurrentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext()
                .getAuthentication().getName());
    }
}