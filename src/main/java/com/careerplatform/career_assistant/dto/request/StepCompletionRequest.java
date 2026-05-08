package com.careerplatform.career_assistant.dto.request;

import lombok.Data;

// request body for PATCH /api/roadmap/{roadmapId}/step/{stepId}
@Data
public class StepCompletionRequest {
    private Boolean completed;
    private Boolean skipped;
}