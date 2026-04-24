package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

// used in GET /api/resume/my-resumes
@Data
@AllArgsConstructor
public class ResumeListResponse {
    private Long resumeId;
    private String fileName;
    private Long fileSize;
    private LocalDateTime uploadTime;
    private Integer overallScore;   // from analysis if exists
    private Integer atsScore;
    private String experienceLevel;
}