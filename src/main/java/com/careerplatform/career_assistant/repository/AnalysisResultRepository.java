package com.careerplatform.career_assistant.repository;

import com.careerplatform.career_assistant.entity.AnalysisResult;
import com.careerplatform.career_assistant.entity.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    // get analysis by resume
    Optional<AnalysisResult> findByResume(Resume resume);

    // check if analysis already exists for a resume
    boolean existsByResume(Resume resume);
}