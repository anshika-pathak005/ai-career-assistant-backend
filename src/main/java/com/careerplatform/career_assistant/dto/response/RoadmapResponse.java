package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

// complete roadmap response sent to frontend
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RoadmapResponse {

    private Long roadmapId;
    private String dreamJob;
    private String status;
    private String summary;
    private Integer totalEstimatedWeeks;
    private LocalDateTime generatedAt;
    private Integer overallProgress;
    private List<PhaseDto> phases;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PhaseDto {
        private Long phaseId;
        private String title;
        private String description;
        private Integer phaseOrder;
        private Integer estimatedWeeks;
        private Integer phaseProgress;
        private List<SkillDto> skills;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SkillDto {
        private Long skillId;
        private String skillName;
        private Integer skillOrder;
        private Integer estimatedDays;
        private String targetLevel;
        private String startingLevel;
        private Boolean isExistingSkill;
        private String skillType;           // SKILL | TOOL | PROJECT
        private String resources;
        private String projectDescription;  // only for PROJECT type
        private String techStack;           // only for PROJECT type (JSON string)
        private Integer skillProgress;      // 0-100
        private List<StepDto> steps;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StepDto {
        private Long stepId;
        private String title;
        private String description;
        private Integer stepOrder;
        private Integer estimatedHours;
        private Boolean completed;
        private Boolean skipped; // new
    }
}