package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResumeUploadResponse {

    private Long resumeId;
    private String fileName;
    private String message;
    private AnalysisDto analysis;
    private CareerGuidanceDto careerGuidance;

    // resume analysis section
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
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
        private List<String> technicalSkills;
        private List<String> softSkills;
        private List<String> strengths;
        private List<String> improvements;
        private List<String> missingSkills;
        private List<String> missingKeywords;
    }

    // career guidance section — based on dream job + profile
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CareerGuidanceDto {
        private List<String> recommendedRoles;
        private List<String> learningRoadmap;
        private List<String> projectSuggestions;
        private String careerAdvice;
    }
}