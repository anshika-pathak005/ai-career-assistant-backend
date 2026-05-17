package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResumeAnalysisResponse {

    private Long resumeId;
    private Long analysisId;
    private String fileName;
    private LocalDateTime uploadedAt;
    private String message;

    private ScoresDto scores;
    private BasicInfoDto basicInfo;
    private AnalysisDto analysis;
    private GenerationLimitResponse usageInfo; // how many analyses used this month

    // ── nested DTOs ───────────────────────────────────────────────

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class ScoresDto {
        private Integer overallScore;
        private Integer atsScore;
        private Integer technicalSkillsScore;
        private Integer projectQualityScore;
        private Integer formattingScore;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class BasicInfoDto {
        private String fullName;
        private String email;
        private String phone;
        private String currentRole;
        private String experienceLevel;
        private Double experienceYears;
        private String interviewReadiness;   // Beginner / Moderate / Job Ready
        private String dreamJobMatchSummary;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class AnalysisDto {
        private String summary;
        private List<String> technicalSkills;
        private List<String> strengths;
        private List<String> weaknesses;
        private List<String> missingSkills;
        private List<String> missingKeywords;
        private List<String> improvementSuggestions;
        private List<String> projectSuggestions;
        private List<String> recommendedLearning;
    }
}