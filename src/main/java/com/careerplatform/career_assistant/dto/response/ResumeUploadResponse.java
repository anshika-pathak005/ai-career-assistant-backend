package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

// complete response after resume upload + analysis
@Data
@AllArgsConstructor
public class ResumeUploadResponse {
    private Long resumeId;
    private String fileName;
    private String message;
    private AnalysisDto analysis;

    @Data
    @AllArgsConstructor
    public static class AnalysisDto {
        private String fullName;
        private String email;
        private String phone;
        private Integer overallScore;
        private Integer atsScore;
        private String summary;
        private String experienceLevel;
        private Double experienceYears;
        private String currentRole;
        private List<String> strengths;
        private List<String> improvements;
        private List<String> missingSkills;
        private List<String> missingKeywords;
        private List<String> technicalSkills;
    }
}