package com.careerplatform.career_assistant.dto.request;

import lombok.Data;
import java.util.List;
import java.util.Map;

// Used for general profile update from profile page
// All fields optional — only non-null fields get updated
@Data
public class UpdateProfileRequest {
    private String currentRole;
    private Integer experienceYears;
    private List<String> industries;
    private List<String> skills;
    private String goalType;
    private String projectLevel;
    private String workPreference;
    private Integer weeklyHours;
    private String learningStyle;
    private Map<String, String> skillLevels;
}