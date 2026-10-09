package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.response.DashboardResponse;
import com.careerplatform.career_assistant.dto.response.GenerationLimitResponse;
import com.careerplatform.career_assistant.entity.*;
import com.careerplatform.career_assistant.enums.RoadmapStatus;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final UserRepository userRepository;
    private final CareerRoadmapRepository roadmapRepository;
    private final ResumeRepository resumeRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final GenerationLimitService generationLimitService;

    /**
     * Builds complete dashboard response for logged in user.
     * Single call — frontend gets everything needed for dashboard.
     * All sections are null-safe — works even for brand new users.
     */
    // One transaction for the whole method, so the lazy roadmap tree resolves
    // inside an open session. readOnly = true: no dirty-checking, no flush.
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(Long userId) {
        log.info("Dashboard request for userId: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return new DashboardResponse(
                buildUserSummary(user),
                buildRoadmapSection(user),
                buildResumeSection(user),
                buildQuickStats(user),
                buildLimits(user),
                buildProfileStatus(user)
        );
    }

    /**
     * Builds user greeting section.
     * Basic info needed to personalize the dashboard header.
     */
    private DashboardResponse.UserSummaryDto buildUserSummary(User user) {
        return new DashboardResponse.UserSummaryDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getDreamJob(),
                user.getPlan().name()   // 5.3: enum -> wire format unchanged ("FREE"/"PRO")
        );
    }

    /**
     * Builds roadmap section with progress details.
     * Returns null if user has no active roadmap.
     * Calculates current phase — phase with progress > 0 and < 100.
     * Calculates overall progress from all steps across all phases.
     */
    private DashboardResponse.RoadmapDashboardDto buildRoadmapSection(User user) {
        Optional<CareerRoadmap> activeRoadmap =
                roadmapRepository.findByUserAndStatus(user, RoadmapStatus.ACTIVE);

        if (activeRoadmap.isEmpty()) return null;

        CareerRoadmap roadmap = activeRoadmap.get();
        List<RoadmapPhase> phases = roadmap.getPhases();

        // calculate overall + per phase progress
        int totalSteps = 0, completedSteps = 0;
        List<DashboardResponse.PhaseProgressDto> phaseProgressList = new ArrayList<>();
        DashboardResponse.CurrentPhaseDto currentPhase = null;

        for (RoadmapPhase phase : phases) {
            int phaseTotal = 0, phaseDone = 0;
            int completedSkills = 0;

            for (RoadmapSkill skill : phase.getSkills()) {
                int skillTotal = skill.getSteps().size();
                int skillDone = (int) skill.getSteps().stream()
                        .filter(s -> Boolean.TRUE.equals(s.getCompleted())
                                || Boolean.TRUE.equals(s.getSkipped()))
                        .count();

                phaseTotal += skillTotal;
                phaseDone  += skillDone;

                if (skillDone == skillTotal && skillTotal > 0) completedSkills++;
            }

            totalSteps    += phaseTotal;
            completedSteps += phaseDone;

            int phaseProgress = phaseTotal > 0 ? (phaseDone * 100 / phaseTotal) : 0;
            boolean isComplete = phaseProgress == 100;

            // current phase = first phase that is not 100% complete
            boolean isCurrent = !isComplete && currentPhase == null;

            if (isCurrent) {
                currentPhase = new DashboardResponse.CurrentPhaseDto(
                        phase.getId(), phase.getTitle(),
                        phase.getPhaseOrder(), phaseProgress,
                        phase.getSkills().size(), completedSkills
                );
            }

            phaseProgressList.add(new DashboardResponse.PhaseProgressDto(
                    phase.getId(), phase.getTitle(),
                    phase.getPhaseOrder(), phaseProgress,
                    isComplete, isCurrent
            ));
        }

        int overallProgress = totalSteps > 0
                ? (completedSteps * 100 / totalSteps) : 0;

        // if all phases complete — set last phase as current
        if (currentPhase == null && !phases.isEmpty()) {
            RoadmapPhase last = phases.get(phases.size() - 1);
            currentPhase = new DashboardResponse.CurrentPhaseDto(
                    last.getId(), last.getTitle(),
                    last.getPhaseOrder(), 100,
                    last.getSkills().size(), last.getSkills().size()
            );
        }

        return new DashboardResponse.RoadmapDashboardDto(
                roadmap.getId(),
                roadmap.getDreamJobSnapshot(),
                null,               // specialization not stored separately yet
                overallProgress,
                roadmap.getTotalEstimatedWeeks(),
                roadmap.getGeneratedAt(),
                currentPhase,
                null,               // lastActivity — completedAt not tracked yet
                phaseProgressList
        );
    }

    /**
     * Builds resume section with latest resume summary.
     * Returns null if user has no resumes.
     * Shows latest upload + total count.
     */
    private DashboardResponse.ResumeDashboardDto buildResumeSection(User user) {
        List<Resume> resumes = resumeRepository.findByUserOrderByUploadTimeDesc(user);

        if (resumes.isEmpty()) return null;

        Resume latest = resumes.get(0);
        Optional<AnalysisResult> analysis = analysisResultRepository.findByResume(latest);

        return analysis.map(a -> new DashboardResponse.ResumeDashboardDto(
                latest.getId(), a.getId(), latest.getFileName(),
                a.getOverallScore(), a.getAtsScore(),
                a.getInterviewReadiness(), a.getResumeVerdict(),
                latest.getUploadTime(), resumes.size()
        )).orElse(new DashboardResponse.ResumeDashboardDto(
                latest.getId(), null, latest.getFileName(),
                null, null, null, null,
                latest.getUploadTime(), resumes.size()
        ));
    }

    /**
     * Builds quick stats section.
     * Counts completed steps, skipped steps, phases done, total resumes.
     * Used for stats cards on dashboard.
     */
    private DashboardResponse.QuickStatsDto buildQuickStats(User user) {
        int totalSteps = 0, completedSteps = 0, skippedSteps = 0, phasesCompleted = 0;

        Optional<CareerRoadmap> activeRoadmap =
                roadmapRepository.findByUserAndStatus(user, RoadmapStatus.ACTIVE);

        if (activeRoadmap.isPresent()) {
            for (RoadmapPhase phase : activeRoadmap.get().getPhases()) {
                int phaseTotal = 0, phaseDone = 0;

                for (RoadmapSkill skill : phase.getSkills()) {
                    for (SkillStep step : skill.getSteps()) {
                        totalSteps++;
                        phaseTotal++;
                        if (Boolean.TRUE.equals(step.getCompleted())) {
                            completedSteps++;
                            phaseDone++;
                        }
                        if (Boolean.TRUE.equals(step.getSkipped())) {
                            skippedSteps++;
                            phaseDone++;
                        }
                    }
                }
                if (phaseTotal > 0 && phaseDone == phaseTotal) phasesCompleted++;
            }
        }

        int totalResumes = (int) resumeRepository.countByUser(user);

        // total roadmaps ever generated including archived
        int totalRoadmaps = roadmapRepository.findByUserOrderByGeneratedAtDesc(user).size();

        return new DashboardResponse.QuickStatsDto(
                completedSteps, skippedSteps, totalSteps,
                phasesCompleted, totalResumes, totalRoadmaps
        );
    }

    /**
     * Builds limits section for both roadmap and resume.
     * Frontend uses this to show usage bars and disable buttons when limit reached.
     */
    private DashboardResponse.LimitsDto buildLimits(User user) {
        GenerationLimitResponse roadmapLimit =
                generationLimitService.buildLimitResponse(user);
        GenerationLimitResponse resumeLimit =
                generationLimitService.buildResumeLimitResponse(user);

        return new DashboardResponse.LimitsDto(
                new DashboardResponse.GenerationLimitDto(
                        roadmapLimit.getUsed(), roadmapLimit.getTotal(),
                        roadmapLimit.getRemaining(), roadmapLimit.getLimitReached(),
                        roadmapLimit.getResetsOn()
                ),
                new DashboardResponse.GenerationLimitDto(
                        resumeLimit.getUsed(), resumeLimit.getTotal(),
                        resumeLimit.getRemaining(), resumeLimit.getLimitReached(),
                        resumeLimit.getResetsOn()
                )
        );
    }

    /**
     * Checks which required profile fields are missing.
     * Frontend shows "Complete your profile" banner if missingFields is not empty.
     * Required fields — dreamJob, skills, userCategory.
     */
    private DashboardResponse.ProfileStatusDto buildProfileStatus(User user) {
        List<String> missing = new ArrayList<>();

        if (user.getDreamJob() == null || user.getDreamJob().isBlank())
            missing.add("dreamJob");
        if (user.getUserCategory() == null || user.getUserCategory().isBlank())
            missing.add("userCategory");
        if (user.getSkills() == null || user.getSkills().isEmpty())
            missing.add("skills");

        return new DashboardResponse.ProfileStatusDto(
                missing.isEmpty(),
                missing
        );
    }

}