package com.careerplatform.career_assistant.repository;

import com.careerplatform.career_assistant.entity.CareerRoadmap;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.enums.RoadmapStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CareerRoadmapRepository extends JpaRepository<CareerRoadmap, Long> {

    // get current active roadmap for user
    Optional<CareerRoadmap> findByUserAndStatus(User user, RoadmapStatus status);

    // get all roadmaps for user — active + archived
    List<CareerRoadmap> findByUserOrderByGeneratedAtDesc(User user);

    // check if user has an active roadmap
    boolean existsByUserAndStatus(User user, RoadmapStatus status);

    /**
     * SECURITY: ownership-scoped lookup. Prefer this over findById() + a manual
     * owner check so the constraint cannot be forgotten at the call site.
     */
    Optional<CareerRoadmap> findByIdAndUserId(Long id, Long userId);

    /**
     * 5.4 — account deletion. Deliberately returns entities rather than issuing a bulk
     * DELETE: the phase -> skill -> step tree is removed by CascadeType.ALL +
     * orphanRemoval, and a bulk JPQL DELETE bypasses cascading entirely, leaving
     * orphaned phases behind with a dangling roadmap_id.
     */
    List<CareerRoadmap> findByUserId(Long userId);
}
