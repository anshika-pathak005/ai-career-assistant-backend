package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DashboardResponse {

    // ── user greeting section ─────────────────────────────────────
    private UserSummaryDto user;

    // ── roadmap section ───────────────────────────────────────────
    private RoadmapDashboardDto roadmap;        // null if no roadmap yet

    // ── resume section ────────────────────────────────────────────
    private ResumeDashboardDto resume;          // null if no resume yet

    // ── quick stats ───────────────────────────────────────────────
    private QuickStatsDto stats;

    // ── limits ───────────────────────────────────────────────────
    private LimitsDto limits;

    // ── profile completeness ──────────────────────────────────────
    private ProfileStatusDto profileStatus;

    // ══ nested DTOs ═══════════════════════════════════════════════

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class UserSummaryDto {
        private Long userId;
        private String name;
        private String email;
        private String dreamJob;
        private String plan;            // FREE or PRO
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class RoadmapDashboardDto {
        private Long roadmapId;
        private String dreamJob;
        private String specialization;
        private Integer overallProgress;        // 0-100
        private Integer totalEstimatedWeeks;
        private LocalDateTime generatedAt;

        // current active phase info
        private CurrentPhaseDto currentPhase;

        // last step user completed
        private LastActivityDto lastActivity;

        // phase wise progress for progress bars
        private List<PhaseProgressDto> phaseProgress;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class CurrentPhaseDto {
        private Long phaseId;
        private String title;           // "Phase 2: Core Skills"
        private Integer phaseOrder;
        private Integer phaseProgress;  // 0-100
        private Integer totalSkills;
        private Integer completedSkills;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class LastActivityDto {
        private String stepTitle;
        private String skillName;
        private String phaseName;
        private LocalDateTime completedAt; // not tracked yet — leave null for now
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class PhaseProgressDto {
        private Long phaseId;
        private String title;
        private Integer phaseOrder;
        private Integer progress;       // 0-100
        private Boolean isComplete;     // true if progress == 100
        private Boolean isCurrent;      // true if this is the active phase
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class ResumeDashboardDto {
        private Long resumeId;
        private Long analysisId;
        private String fileName;
        private Integer overallScore;
        private Integer atsScore;
        private String interviewReadiness;  // Beginner / Moderate / Job Ready
        private String resumeVerdict;       // one line summary
        private LocalDateTime uploadedAt;
        private Integer totalResumesUploaded;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class QuickStatsDto {
        private Integer totalStepsCompleted;
        private Integer totalStepsSkipped;
        private Integer totalSteps;
        private Integer totalPhasesCompleted;
        private Integer totalResumesAnalyzed;
        private Integer totalRoadmapsGenerated; // includes archived
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class LimitsDto {
        private GenerationLimitDto roadmapLimit;
        private GenerationLimitDto resumeLimit;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class GenerationLimitDto {
        private Integer used;
        private Integer total;
        private Integer remaining;
        private Boolean limitReached;
        private String resetsOn;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class ProfileStatusDto {
        private Boolean isComplete;
        private List<String> missingFields;  // ["dreamJob", "skills"] — empty if complete
    }
}