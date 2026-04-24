package com.careerplatform.career_assistant.exception;

// thrown when file validation fails or file cannot be saved
// GlobalExceptionHandler catches this and returns 400
public class FileUploadException extends RuntimeException {
    public FileUploadException(String message) {
        super(message);
    }
}