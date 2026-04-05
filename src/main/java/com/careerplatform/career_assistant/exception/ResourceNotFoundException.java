package com.careerplatform.career_assistant.exception;

// Throw this when any entity is not found in DB — auto caught by GlobalExceptionHandler as 404
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}