package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

// used in GET /api/v1/resume/my-resumes
// lightweight — no full analysis, just summary for list view
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResumeListItemResponse {
    private Long resumeId;
    private Long analysisId;
    private String fileName;
    private LocalDateTime uploadedAt;
    private Integer overallScore;
    private Integer atsScore;
    private String interviewReadiness;
    private String experienceLevel;
}