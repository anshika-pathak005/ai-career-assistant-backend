package com.careerplatform.career_assistant.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 6.6: every free-text field on this DTO reaches a Groq prompt, so every one of them is
 * now capped. Uncapped, they are two problems at once: a value longer than its column
 * fails the insert with a truncation error surfacing as a 500, and a very long value
 * inflates the token bill on an endpoint that already costs the most per call.
 *
 * 6.3: also dropped an unused `import okhttp3.FormBody`, which had an HTTP client's
 * classes leaking into a request DTO.
 */
@Data
public class RoadmapGenerationRequest {

    // ── editable profile fields ───────────────────────────────────────────────
    // pre-filled from profile, user can update in form
    // null = user did not change = keep existing DB value

    @Size(max = 100, message = "Current role must be 100 characters or fewer")
    private String currentRole;

    @Size(max = 20, message = "Please select 20 industries or fewer")
    private List<@Size(max = 60, message = "Industry name is too long") String> industries;

    @Size(max = 100, message = "Please list 100 skills or fewer")
    private List<@Size(max = 60, message = "Skill name is too long") String> skills;

    @Size(max = 50, message = "Work preference must be 50 characters or fewer")
    private String workPreference;

    // ── required for roadmap generation ──────────────────────────────────────

    @NotNull(message = "Experience level is required")
    @Size(max = 30, message = "Experience level must be 30 characters or fewer")
    private String experienceYears;

    @NotNull(message = "Goal type is required")
    @Size(max = 30, message = "Goal type must be 30 characters or fewer")
    private String goalType;

    @NotNull(message = "Project level is required")
    @Size(max = 30, message = "Project level must be 30 characters or fewer")
    private String projectLevel;

    @NotNull(message = "Learning style is required")
    @Size(max = 30, message = "Learning style must be 30 characters or fewer")
    private String learningStyle;

    /**
     * 6.6: was @NotNull and otherwise unbounded, so weeklyHours = 999999 went straight
     * into the prompt and asked the model to plan a schedule around it. 80 is already
     * generous for something described as study hours per week.
     */
    @NotNull(message = "Weekly hours is required")
    @Min(value = 1, message = "Weekly hours must be at least 1")
    @Max(value = 80, message = "Weekly hours must be 80 or fewer")
    private Integer weeklyHours;

    // skill name → SkillLevel; at least one required
    @NotEmpty(message = "At least one skill with level is required")
    @Size(max = 100, message = "Please provide levels for 100 skills or fewer")
    private Map<@Size(max = 60) String, @Size(max = 30) String> skillLevels;

    @Size(max = 100, message = "Specialization must be 100 characters or fewer")
    private String specialization;
}
