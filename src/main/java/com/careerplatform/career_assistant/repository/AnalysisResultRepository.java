package com.careerplatform.career_assistant.repository;

import com.careerplatform.career_assistant.entity.AnalysisResult;
import com.careerplatform.career_assistant.entity.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    // get analysis by resume
    Optional<AnalysisResult> findByResume(Resume resume);

    // check if analysis already exists for a resume
    boolean existsByResume(Resume resume);

    /**
     * 5.4 — account deletion. Must run before the resumes are deleted: resume_id is
     * NOT NULL, so removing a resume that still has an analysis row is a foreign key
     * violation, which is what the old deleteAccount() hit.
     */
    @Modifying
    @Query("DELETE FROM AnalysisResult a WHERE a.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}