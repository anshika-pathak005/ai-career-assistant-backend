package com.careerplatform.career_assistant.dto.request;

import com.careerplatform.career_assistant.enums.*;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import okhttp3.FormBody;

import java.util.List;
import java.util.Map;

@Data
public class RoadmapGenerationRequest {

    // ── editable profile fields ───────────────────────────────────────────────
    // pre-filled from profile, user can update in form
    // null = user did not change = keep existing DB value
    private String currentRole;
    private List<String> industries;
    private List<String> skills;            // skill names — updated if user added more
    private String workPreference;

    // ── required for roadmap generation ──────────────────────────────────────

    @NotNull(message = "Experience level is required")
    private String experienceYears;

    @NotNull(message = "Goal type is required")
    private String goalType;

    @NotNull(message = "Project level is required")
    private String projectLevel;

    @NotNull(message = "Learning style is required")
    private String learningStyle;

    @NotNull(message = "Weekly hours is required")
    private Integer weeklyHours;            // actual hours: 5, 10, 20

    // skill name → SkillLevel enum
    // at least one skill with level required
    @NotEmpty(message = "At least one skill with level is required")
    private Map<String, String> skillLevels;

    private String specialization;
}