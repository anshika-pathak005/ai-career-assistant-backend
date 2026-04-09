package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private Long userId;
    private String email;
    private String name;
    private Integer wizardStep;
    private Boolean isProfileComplete;
}