package com.careerplatform.career_assistant.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// request body for refresh token endpoint
@Data
public class RefreshRequest {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}