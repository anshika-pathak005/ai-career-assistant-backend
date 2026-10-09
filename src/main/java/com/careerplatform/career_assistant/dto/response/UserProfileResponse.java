package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class UserProfileResponse {
    private Long userId;
    private String name;
    private String email;
    private String userCategory;
    private String dreamJob;
    private List<String> industries;
    private List<String> skills;
    private Integer experienceYears;
    private String currentRole;
    private String goalType;
    private String projectLevel;
    private String workPreference;
    private Integer weeklyHours;      // new
    private String learningStyle;     // new
    private String skillLevels;       // new — JSON string {"React": "Intermediate"}
    private Integer wizardStep;
    private Boolean isProfileComplete;
    private GenerationLimitResponse generationLimit;
    private GenerationLimitResponse resumeUsage;
    private RoadmapSummaryDto activeRoadmap;    // null if no roadmap yet
    private ResumeSummaryDto latestResume;       // null if no resume yet

    /*
     * 7.1: when this account is due to be deleted, or null when no deletion is pending.
     *
     * Additive, so existing clients are unaffected. It is here rather than only in the
     * response to the delete call because the grace period is worthless if the user
     * cannot see that a deletion is pending on a later visit - that visit is the only
     * notification this app currently has.
     */
    private LocalDateTime deletionScheduledFor;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RoadmapSummaryDto {
        private Long roadmapId;
        private String dreamJob;
        private String status;
        private Integer overallProgress;
        private Integer totalEstimatedWeeks;
        private LocalDateTime generatedAt;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ResumeSummaryDto {
        private Long resumeId;
        private Long analysisId;
        private String fileName;
        private Integer overallScore;
        private Integer atsScore;
        private String interviewReadiness;
        private LocalDateTime uploadedAt;
    }
}