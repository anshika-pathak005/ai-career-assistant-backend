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
}