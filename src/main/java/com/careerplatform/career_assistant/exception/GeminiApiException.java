package com.careerplatform.career_assistant.exception;

// thrown when Gemini API call fails or returns unexpected response
// GlobalExceptionHandler catches this and returns 502
public class GeminiApiException extends RuntimeException {
    public GeminiApiException(String message) {
        super(message);
    }
}