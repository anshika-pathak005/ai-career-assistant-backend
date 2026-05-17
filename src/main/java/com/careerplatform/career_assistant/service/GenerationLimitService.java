package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.response.GenerationLimitResponse;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.RoadmapLimitExceededException;
import com.careerplatform.career_assistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationLimitService {

    @Value("${app.generation.free-limit}")
    private int freeLimit;
    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM");

    private final UserRepository userRepository;

    // limit type enum — internal use only
    public enum LimitType {
        ROADMAP, RESUME
    }

    /**
     * Checks if user can generate a roadmap.
     * Resets counter if month has changed since last generation.
     * Throws RoadmapLimitExceededException if limit reached.
     * Call this BEFORE generating roadmap.
     */
    public void checkAndIncrement(User user) {
        checkAndIncrement(user, LimitType.ROADMAP);
    }
    /**
     * Checks and increments limit for given type.
     * ROADMAP → checks roadmapsGeneratedThisMonth
     * RESUME  → checks resumesAnalyzedThisMonth
     */
    public void checkAndIncrement(User user, LimitType type) {
        String currentMonth = YearMonth.now().format(MONTH_FORMAT);

        // reset roadmap counter if new month
        if (!currentMonth.equals(user.getCurrentMonthYear())) {
            log.info("New month detected — resetting roadmap counter for userId: {}", user.getId());
            user.setRoadmapsGeneratedThisMonth(0);
            user.setResumesAnalyzedThisMonth(0);  // reset both on new month
            user.setCurrentMonthYear(currentMonth);
        }

        if (type == LimitType.RESUME) {
            if (!"PRO".equals(user.getPlan()) &&
                    user.getResumesAnalyzedThisMonth() >= freeLimit) {
                log.warn("Resume limit reached for userId: {}", user.getId());
                throw new RoadmapLimitExceededException(
                        "You have used all " + freeLimit + " resume analyses for this month. " +
                                "Upgrade to PRO for unlimited analyses."
                );
            }
            // increment counter
            user.setResumesAnalyzedThisMonth(user.getResumesAnalyzedThisMonth() + 1);
        } else {
            if (!"PRO".equals(user.getPlan()) &&
                    user.getRoadmapsGeneratedThisMonth() >= freeLimit) {
                log.warn("Roadmap limit reached for userId: {}", user.getId());
                throw new RoadmapLimitExceededException(
                        "You have used all " + freeLimit + " roadmap generations for this month. " +
                                "Upgrade to PRO for unlimited generations."
                );
            }
            // increment counter
            user.setRoadmapsGeneratedThisMonth(user.getRoadmapsGeneratedThisMonth() + 1);
        }

        userRepository.save(user);
    }



    /**
     * Builds GenerationLimitResponse for a user.
     * Called after generation and on GET /api/user/usage.
     * Always reflects current state after any counter updates.
     */
    public GenerationLimitResponse buildLimitResponse(User user) {
        String currentMonth = YearMonth.now().format(MONTH_FORMAT);

        // if month changed and user hasn't generated yet — show 0 used
        int used = currentMonth.equals(user.getCurrentMonthYear())
                ? user.getRoadmapsGeneratedThisMonth() : 0;

        int total = getLimit(user.getPlan());

        // PRO users — show unlimited
        if ("PRO".equals(user.getPlan())) {
            return new GenerationLimitResponse(
                    used, -1, -1, "PRO",
                    getFirstDayOfNextMonth(), false
            );
        }

        int remaining = Math.max(0, total - used);

        return new GenerationLimitResponse(
                used, total, remaining,
                user.getPlan(),
                getFirstDayOfNextMonth(),
                used >= total
        );
    }

    /**
     * Builds limit response for resume analysis usage.
     */
    public GenerationLimitResponse buildResumeLimitResponse(User user) {
        String currentMonth = YearMonth.now().format(MONTH_FORMAT);
        int used = currentMonth.equals(user.getCurrentMonthYear())
                ? user.getResumesAnalyzedThisMonth() : 0;
        int total = getLimit(user.getPlan());

        if ("PRO".equals(user.getPlan())) {
            return new GenerationLimitResponse(used, -1, -1, "PRO",
                    getFirstDayOfNextMonth(), false);
        }

        int remaining = Math.max(0, total - used);
        return new GenerationLimitResponse(used, total, remaining,
                user.getPlan(), getFirstDayOfNextMonth(), used >= total);
    }


    // returns max generations based on plan
    private int getLimit(String plan) {
        return "PRO".equals(plan) ? Integer.MAX_VALUE : freeLimit;
    }

    // returns "2026-06-01" — when counter resets
    private String getFirstDayOfNextMonth() {
        return YearMonth.now().plusMonths(1)
                .atDay(1)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}