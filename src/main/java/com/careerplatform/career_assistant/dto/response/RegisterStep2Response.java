package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

// Response DTO for Step 2 — confirms what was saved
@Data
@AllArgsConstructor
public class RegisterStep2Response {
    private String message;
    private Long userId;
    private String userCategory;
    private String dreamJob;
    private Integer wizardStep;
    private Boolean isProfileComplete;
}