package com.careerplatform.career_assistant.integration;

import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.repository.CareerRoadmapRepository;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.support.IntegrationTest;
import com.careerplatform.career_assistant.support.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan item 3.4 — the check-then-increment quota race.
 *
 * The old code read the counter, compared it to the limit, and wrote the counter back.
 * Two requests arriving together both read the same value, both decided they were under
 * the limit, and both generated — so a user on a 3/month plan could get 6 by clicking
 * twice. The fix folds the limit into the WHERE clause of a single conditional UPDATE,
 * so the database decides and the loser gets zero rows back.
 *
 * <b>A concurrency bug cannot be tested by reading the code, which is exactly how it
 * survived.</b> The single-threaded path looks correct at every line.
 *
 * This test drives the repository directly rather than going through the controller.
 * That is deliberate: the thing under test is the atomicity of one SQL statement, and
 * routing through HTTP would add the rate limiter, the AI call and a mock, any of which
 * could mask the result or make the test flaky for unrelated reasons.
 */
@IntegrationTest
@DisplayName("3.4 — concurrent generations cannot both pass the same quota limit")
class GenerationQuotaConcurrencyTest {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int THREADS = 8;

    @Autowired UserRepository userRepository;
    @Autowired CareerRoadmapRepository roadmapRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactionManager;

    private TestDataFactory data;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(userRepository, roadmapRepository, passwordEncoder);
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("Eight simultaneous attempts against a limit of 3 succeed exactly 3 times")
    void concurrentConsumesRespectTheLimit() throws Exception {
        User user = data.user();
        Long userId = user.getId();
        String month = YearMonth.now().format(MONTH);
        int limit = 3;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        // A latch, not just submitting eight tasks. Without it the first task usually
        // finishes before the last one starts and the race never happens — the test would
        // pass against the BROKEN code, which is worse than not having it.
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();

        try {
            Callable<Void> attempt = () -> {
                startGun.await();
                // Each attempt in its own transaction, which is what two separate HTTP
                // requests would produce.
                Integer rows = tx.execute(status ->
                        userRepository.tryConsumeRoadmapQuota(userId, month, limit));
                if (rows != null && rows > 0) {
                    granted.incrementAndGet();
                }
                return null;
            };

            Future<?>[] futures = new Future<?>[THREADS];
            for (int i = 0; i < THREADS; i++) {
                futures[i] = pool.submit(attempt);
            }
            startGun.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted.get())
                .as("exactly %d of %d concurrent attempts may be granted", limit, THREADS)
                .isEqualTo(limit);

        // And the stored counter agrees. A test that only counted successful calls would
        // pass against an implementation that granted correctly but double-counted.
        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getRoadmapsGeneratedThisMonth()).isEqualTo(limit);
        assertThat(reloaded.getCurrentMonthYear()).isEqualTo(month);
    }

    @Test
    @DisplayName("A counter left over from last month resets rather than blocking")
    void staleMonthResetsInsteadOfBlocking() {
        User user = data.user();
        Long userId = user.getId();

        // Simulate a user who exhausted their quota in a previous month.
        tx.executeWithoutResult(status -> {
            User u = userRepository.findById(userId).orElseThrow();
            u.setRoadmapsGeneratedThisMonth(3);
            u.setCurrentMonthYear(YearMonth.now().minusMonths(1).format(MONTH));
            userRepository.save(u);
        });

        String thisMonth = YearMonth.now().format(MONTH);
        Integer rows = tx.execute(status ->
                userRepository.tryConsumeRoadmapQuota(userId, thisMonth, 3));

        // The rollover is folded into the same statement on purpose. As a separate
        // read-modify-write, two requests on the 1st could both see the stale month, both
        // reset to zero, and both consume the first unit.
        assertThat(rows).isEqualTo(1);

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getRoadmapsGeneratedThisMonth())
                .as("the counter resets to 1, it does not continue from 3")
                .isEqualTo(1);
        assertThat(reloaded.getCurrentMonthYear()).isEqualTo(thisMonth);
    }
}
