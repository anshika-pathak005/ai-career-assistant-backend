package com.careerplatform.career_assistant.exception;

/**
 * Thrown when a user attempts an action (e.g. roadmap generation, resume analysis)
 * that requires a completed profile before finishing their profile setup.
 *
 * GlobalExceptionHandler catches this and returns 400 BAD_REQUEST.
 */
public class IncompleteProfileException extends RuntimeException {
    public IncompleteProfileException(String message) {
        super(message);
    }
}
