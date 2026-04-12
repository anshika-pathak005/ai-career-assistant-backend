package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

// returns new access token + new refresh token (rotation)
@Data
@AllArgsConstructor
public class RefreshResponse {
    private String accessToken;
    private String refreshToken; // new rotated refresh token
}