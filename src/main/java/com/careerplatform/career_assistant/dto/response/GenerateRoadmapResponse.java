package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

// generate endpoint returns only roadmapId
// frontend uses this to redirect to /roadmap/{id}
// full roadmap fetched separately via GET /api/v1/roadmap/{id}
@Data
@AllArgsConstructor
public class GenerateRoadmapResponse {
    private Long roadmapId;
    private String message;
    private GenerationLimitResponse generationLimit;
}