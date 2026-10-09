package com.careerplatform.career_assistant.repository;

import com.careerplatform.career_assistant.entity.SkillStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SkillStepRepository extends JpaRepository<SkillStep, Long> {

    /**
     * Loads a step ONLY if it belongs to the given roadmap AND that roadmap
     * belongs to the given user.
     *
     * SECURITY: the ownership constraint is part of the WHERE clause on purpose.
     * The previous code fetched the step with findById() and relied on a separate
     * check that never verified the step was inside that roadmap — so any
     * authenticated user could mutate any step in the database by passing their
     * own roadmapId together with someone else's stepId.
     *
     * Encoding the rule in the query makes the unsafe call impossible to write:
     * there is no repository method that returns a step you do not own.
     *
     * Walks: SkillStep -> RoadmapSkill -> RoadmapPhase -> CareerRoadmap -> User
     */
    @Query("""
            select s
            from SkillStep s
            where s.id = :stepId
              and s.skill.phase.roadmap.id = :roadmapId
              and s.skill.phase.roadmap.user.id = :userId
            """)
    Optional<SkillStep> findOwnedStep(@Param("stepId") Long stepId,
                                      @Param("roadmapId") Long roadmapId,
                                      @Param("userId") Long userId);
}
