package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.response.GenerationLimitResponse;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.enums.Plan;
import com.careerplatform.career_assistant.exception.RoadmapLimitExceededException;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.config.properties.AppProperties;
import com.careerplatform.career_assistant.observability.AppMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Manages the monthly generation quota for roadmaps and resume analyses.
 *
 * Quota is consumed before the AI call, so parallel requests cannot all slip past the
 * limit, and refunded if that call fails.
 * The counter resets on the 1st of each month in the configured timezone.
 * FREE plans get a fixed limit; PRO is unlimited but still counted, so usage reporting
 * stays accurate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationLimitService {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final UserRepository userRepository;

    /**
     * 6.4: free-limit and reset-zone were two @Value fields. reset-zone in particular was a
     * String fed to ZoneId.of() on every quota check, so a typo in the property turned
     * into a 500 on the first generation of the month rather than a startup failure.
     * AppProperties binds it as a ZoneId, which means an invalid zone cannot boot.
     */
    private final AppProperties appProperties;

    /** 7.2: how often users run out of quota - see the counter at the rejection below. */
    private final AppMetrics metrics;

    /** The FREE plan's monthly allowance. Read through AppProperties, not injected raw. */
    private int freeLimit() {
        return appProperties.generation().freeLimit();
    }

    /** Timezone the monthly counter resets in. Bound as a ZoneId, so it cannot be invalid. */
    private ZoneId resetZone() {
        return appProperties.generation().resetZone();
    }

    public enum LimitType {
        ROADMAP, RESUME
    }

    /**
     * Consumes one unit of quota for the user, or throws if they are out.
     * The check and the increment happen as one conditional UPDATE, so two concurrent
     * requests cannot both pass the same limit.
     *
     * Takes a userId rather than a User so there is no count to read off an entity and
     * race against.
     *
     * Must run inside the caller's transaction, so a rollback also rolls back the
     * consumption — that is why there is no REQUIRES_NEW here.
     */
    public void consume(Long userId, LimitType type) {
        String month = currentMonth();

        int rowsUpdated = (type == LimitType.RESUME)
                ? userRepository.tryConsumeResumeQuota(userId, month, freeLimit())
                : userRepository.tryConsumeRoadmapQuota(userId, month, freeLimit());

        // Zero rows means the WHERE clause rejected it, which can only be the limit
        // condition — the id is known to exist because the caller just loaded it.
        if (rowsUpdated == 0) {
            log.warn("{} limit reached for userId: {}", type, userId);
            /*
             * 7.2: plan is hardcoded FREE rather than read from the user, and that is
             * accurate rather than lazy - the UPDATE's WHERE clause exempts PRO, so a
             * PRO user can never reach this branch. Loading the row to tag it would be
             * an extra query to learn something the code already guarantees.
             *
             * This counter answers "is the free limit set sensibly". A number that
             * climbs steadily is a pricing signal; one that spikes is usually a client
             * retrying a failed generation in a loop.
             */
            metrics.quotaRejected("FREE", type.name());
            throw new RoadmapLimitExceededException(buildLimitMessage(type));
        }
    }

    /**
     * Refunds one unit of quota after a failed generation.
     *
     * REQUIRES_NEW so it still commits while the caller is unwinding an exception — the
     * caller's transaction is already marked for rollback by that point, and a refund
     * written into it would be discarded along with everything else.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long userId, LimitType type) {
        String month = currentMonth();

        int rowsUpdated = (type == LimitType.RESUME)
                ? userRepository.releaseResumeQuota(userId, month)
                : userRepository.releaseRoadmapQuota(userId, month);

        if (rowsUpdated > 0) {
            log.info("Released one {} quota unit for userId: {} after a failed generation",
                    type, userId);
        }
    }

    /**
     * Builds the roadmap usage response.
     * Called after generation and on GET /api/v1/user/usage.
     */
    public GenerationLimitResponse buildLimitResponse(User user) {
        return buildResponse(user, usedThisMonth(user, LimitType.ROADMAP));
    }

    /** Builds the resume analysis usage response. */
    public GenerationLimitResponse buildResumeLimitResponse(User user) {
        return buildResponse(user, usedThisMonth(user, LimitType.RESUME));
    }

    // ---------------------------------------------------------------- helpers

    /** Assembles the usage response; PRO shows -1 for total and remaining. */
    private GenerationLimitResponse buildResponse(User user, int used) {
        // 5.3: was "PRO".equals(user.getPlan()) against a String field. Now a reference
        // comparison the compiler checks — a typo is a build error, not a silent denial.
        if (user.getPlan() == Plan.PRO) {
            return new GenerationLimitResponse(used, -1, -1, Plan.PRO.name(),
                    firstDayOfNextMonth(), false);
        }

        int remaining = Math.max(0, freeLimit() - used);
        // .name() keeps the JSON wire format identical to before: "FREE" / "PRO".
        return new GenerationLimitResponse(used, freeLimit(), remaining,
                user.getPlan().name(), firstDayOfNextMonth(), used >= freeLimit());
    }

    /**
     * Reads the stored counter, but only if it belongs to the current month.
     * The counter is reset lazily by the next consume, so between the 1st and a user's
     * first generation the row still holds last month's number.
     */
    private int usedThisMonth(User user, LimitType type) {
        if (!currentMonth().equals(user.getCurrentMonthYear())) {
            return 0;
        }
        Integer used = (type == LimitType.RESUME)
                ? user.getResumesAnalyzedThisMonth()
                : user.getRoadmapsGeneratedThisMonth();
        return used != null ? used : 0;
    }

    private String buildLimitMessage(LimitType type) {
        String noun = (type == LimitType.RESUME) ? "resume analyses" : "roadmap generations";
        return "You have used all " + freeLimit() + " " + noun + " for this month. "
                + "Upgrade to PRO for unlimited access.";
    }

    /** Current month as "yyyy-MM" in the configured reset timezone. */
    private String currentMonth() {
        return YearMonth.now(resetZone()).format(MONTH_FORMAT);
    }

    /** When the counter resets, e.g. "2026-10-01". */
    private String firstDayOfNextMonth() {
        return YearMonth.now(resetZone())
                .plusMonths(1)
                .atDay(1)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
