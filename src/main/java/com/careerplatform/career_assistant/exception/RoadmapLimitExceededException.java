package com.careerplatform.career_assistant.exception;

// thrown when user hits monthly roadmap generation limit
// GlobalExceptionHandler catches this and returns 429
public class RoadmapLimitExceededException extends RuntimeException {
    public RoadmapLimitExceededException(String message) {
        super(message);
    }
}