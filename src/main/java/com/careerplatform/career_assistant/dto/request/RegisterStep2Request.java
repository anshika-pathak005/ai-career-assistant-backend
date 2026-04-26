package com.careerplatform.career_assistant.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

// Request DTO for Step 2 registration
// Only userCategory and dreamJob are required — rest are optional
@Data
public class RegisterStep2Request {

    @NotBlank(message = "User category is required")
    private String userCategory;   // Student / Fresher / Professional

    @NotBlank(message = "Dream job is required")
    private String dreamJob;

    private List<String> industries;      // optional — multi-select
    private List<String> skills;          // optional — tags input
    private Integer experienceYears;      // optional
    private String currentRole;           // optional
    private String goalType;
    private String projectLevel;
    private String workPreference;
}