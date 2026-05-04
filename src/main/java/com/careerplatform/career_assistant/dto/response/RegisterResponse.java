package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

// Response DTO for Step 1 registration
// Keeps response structure typed and consistent — never return raw HashMap from controllers
@Data
@AllArgsConstructor
public class RegisterResponse {
    private String message;
    private Long userId;
    private String email;
    private String name;
    private Integer wizardStep;
    private String accessToken;
}