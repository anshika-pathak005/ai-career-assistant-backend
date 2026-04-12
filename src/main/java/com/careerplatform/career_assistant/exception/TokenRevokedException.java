package com.careerplatform.career_assistant.exception;

// thrown when refresh token is revoked or expired
// GlobalExceptionHandler catches this and returns 401
public class TokenRevokedException extends RuntimeException {
    public TokenRevokedException(String message) {
        super(message);
    }
}