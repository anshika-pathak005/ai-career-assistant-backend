package com.careerplatform.career_assistant.exception;

// Throw this when a unique resource already exists (e.g. email already registered)
// GlobalExceptionHandler catches this and returns 409 CONFLICT
public class DuplicateResourceException extends RuntimeException {
    public DuplicateResourceException(String message) {
        super(message);
    }
}