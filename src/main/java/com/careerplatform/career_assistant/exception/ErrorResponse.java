package com.careerplatform.career_assistant.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.LocalDateTime;

// Shape of the error JSON sent to frontend
@Data
@AllArgsConstructor
public class ErrorResponse {
    private int status;
    private String message;
    private LocalDateTime timestamp;
}