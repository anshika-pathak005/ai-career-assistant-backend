package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

// internal wrapper — never sent to frontend
// used to pass both tokens from service to controller
@Data
@AllArgsConstructor
public class AuthResult {
    private LoginResponse loginResponse; // goes to response body
    private String rawRefreshToken;      // goes to httpOnly cookie
}