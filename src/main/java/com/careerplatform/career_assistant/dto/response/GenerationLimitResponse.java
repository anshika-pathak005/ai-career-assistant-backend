package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// included in roadmap generation response + GET /api/user/usage
// frontend uses this to show "2/3 roadmaps used this month"
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GenerationLimitResponse {
    private Integer used;        // how many generated this month
    private Integer total;       // max allowed (3 for FREE, unlimited for PRO)
    private Integer remaining;   // total - used
    private String plan;         // FREE or PRO
    private String resetsOn;     // "2026-06-01" — first day of next month
    private Boolean limitReached; // true if used >= total
}