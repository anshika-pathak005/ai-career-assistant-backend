package com.careerplatform.career_assistant.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * General profile update from the profile page. All fields optional — only non-null
 * fields are written.
 *
 * 6.6: the caps are new, and so is the @Valid on the controller parameter. Without that
 * annotation these constraints would never run.
 */
@Data
public class UpdateProfileRequest {

    @Size(max = 100, message = "Current role must be 100 characters or fewer")
    private String currentRole;

    @Min(value = 0, message = "Experience years cannot be negative")
    @Max(value = 60, message = "Experience years must be 60 or fewer")
    private Integer experienceYears;

    @Size(max = 20, message = "Please select 20 industries or fewer")
    private List<@Size(max = 60) String> industries;

    @Size(max = 100, message = "Please list 100 skills or fewer")
    private List<@Size(max = 60) String> skills;

    @Size(max = 30, message = "Goal type must be 30 characters or fewer")
    private String goalType;

    @Size(max = 30, message = "Project level must be 30 characters or fewer")
    private String projectLevel;

    @Size(max = 50, message = "Work preference must be 50 characters or fewer")
    private String workPreference;

    @Min(value = 1, message = "Weekly hours must be at least 1")
    @Max(value = 80, message = "Weekly hours must be 80 or fewer")
    private Integer weeklyHours;

    @Size(max = 30, message = "Learning style must be 30 characters or fewer")
    private String learningStyle;

    @Size(max = 100, message = "Please provide levels for 100 skills or fewer")
    private Map<@Size(max = 60) String, @Size(max = 30) String> skillLevels;
}
