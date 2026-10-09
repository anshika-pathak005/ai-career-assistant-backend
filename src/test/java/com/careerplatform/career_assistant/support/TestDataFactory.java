package com.careerplatform.career_assistant.support;

import com.careerplatform.career_assistant.entity.CareerRoadmap;
import com.careerplatform.career_assistant.entity.RoadmapPhase;
import com.careerplatform.career_assistant.entity.RoadmapSkill;
import com.careerplatform.career_assistant.entity.SkillStep;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.enums.Plan;
import com.careerplatform.career_assistant.enums.RoadmapStatus;
import com.careerplatform.career_assistant.repository.CareerRoadmapRepository;
import com.careerplatform.career_assistant.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the fixtures the integration tests need.
 *
 * <b>Deliberately a plain class, not a @Component.</b> Spring's component scan starts at
 * com.careerplatform.career_assistant and covers the test classpath, so a @Component here
 * would be registered into EVERY test context — including ones that should not know it
 * exists — and would become a hidden dependency of the production bean graph the moment
 * someone autowired it by accident. Tests construct it explicitly instead.
 *
 * Emails are made unique per instance because these tests do not roll back (see
 * AbstractDataTest) and `users.email` is a unique column; a fixed address turns the
 * second test in a class into a constraint violation.
 */
public class TestDataFactory {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    public static final String DEFAULT_PASSWORD = "correct-horse-battery";

    private final UserRepository userRepository;
    private final CareerRoadmapRepository roadmapRepository;
    private final PasswordEncoder passwordEncoder;

    public TestDataFactory(UserRepository userRepository,
                           CareerRoadmapRepository roadmapRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roadmapRepository = roadmapRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** A persisted FREE user with a known password. */
    public User user() {
        return user(Plan.FREE);
    }

    public User user(Plan plan) {
        String unique = "t" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        User user = User.builder()
                .email(unique + "@example.test")
                .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                .name("Test User " + unique)
                .plan(plan)
                // currentMonthYear is intentionally left at its "" default: that is the
                // state a freshly registered user is in, and the quota queries have to
                // cope with it. Pre-setting it here would test a state real users skip.
                .build();
        return userRepository.save(user);
    }

    /**
     * A roadmap owned by {@code owner} with one phase, one skill and one step.
     *
     * The full four-level tree, not a bare roadmap row, because the two things these
     * fixtures are used for both depend on it: the ownership query in
     * SkillStepRepository.findOwnedStep walks
     * step -> skill -> phase -> roadmap -> user, and the cascade delete in
     * UserSettingsService.purgeAccount has to unwind the same chain. A one-level fixture
     * would let both of those pass without proving anything.
     */
    public CareerRoadmap roadmapWithOneStep(User owner) {
        CareerRoadmap roadmap = CareerRoadmap.builder()
                .user(owner)
                .dreamJobSnapshot("Full Stack Developer")
                .status(RoadmapStatus.ACTIVE)
                .totalEstimatedWeeks(12)
                .summary("Test roadmap")
                .generatedAt(LocalDateTime.now())
                .build();

        RoadmapPhase phase = RoadmapPhase.builder()
                .roadmap(roadmap)
                .title("Phase 1")
                .description("Foundations")
                .phaseOrder(1)
                .estimatedWeeks(4)
                .build();

        RoadmapSkill skill = RoadmapSkill.builder()
                .phase(phase)
                .skillName("React.js")
                .skillOrder(1)
                .estimatedDays(10)
                .skillType("SKILL")
                .build();

        SkillStep step = SkillStep.builder()
                .skill(skill)
                .title("Learn JSX basics")
                .description("Read the docs, build a component")
                .stepOrder(1)
                .estimatedHours(4)
                .completed(false)
                .skipped(false)
                .build();

        skill.setSteps(List.of(step));
        phase.setSkills(List.of(skill));
        roadmap.setPhases(List.of(phase));

        // One save: CascadeType.ALL on each level persists the whole tree. If this ever
        // starts throwing TransientObjectException, a cascade has been removed and
        // purgeAccount is broken too.
        return roadmapRepository.save(roadmap);
    }

    /** The single step inside a fixture built by {@link #roadmapWithOneStep}. */
    public static SkillStep onlyStepOf(CareerRoadmap roadmap) {
        return roadmap.getPhases().get(0).getSkills().get(0).getSteps().get(0);
    }
}
