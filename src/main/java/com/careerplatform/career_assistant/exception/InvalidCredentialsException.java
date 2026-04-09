package com.careerplatform.career_assistant.exception;

// Thrown when email not found or password is wrong
// GlobalExceptionHandler catches this and returns 401 UNAUTHORIZED
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}