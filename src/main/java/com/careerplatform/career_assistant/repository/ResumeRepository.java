package com.careerplatform.career_assistant.repository;

import com.careerplatform.career_assistant.entity.Resume;
import com.careerplatform.career_assistant.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    // get all resumes for a user — ordered by latest first
    List<Resume> findByUserOrderByUploadTimeDesc(User user);

    // count how many resumes a user has uploaded
    long countByUser(User user);
}