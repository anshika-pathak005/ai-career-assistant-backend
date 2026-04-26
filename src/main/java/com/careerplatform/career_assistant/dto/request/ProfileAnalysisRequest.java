package com.careerplatform.career_assistant.dto.request;

import lombok.Data;
import java.util.List;

// Used when user has no resume — updates profile and triggers AI analysis
// All fields optional — only non-null fields update the user profile
@Data
public class ProfileAnalysisRequest {
    private String userCategory;
    private String dreamJob;
    private List<String> industries;
    private List<String> skills;
    private Integer experienceYears;
    private String currentRole;
    private String goalType;
    private String projectLevel;
    private String workPreference;
}