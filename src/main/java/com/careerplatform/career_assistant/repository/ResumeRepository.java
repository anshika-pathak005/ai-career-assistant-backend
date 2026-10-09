package com.careerplatform.career_assistant.repository;

import com.careerplatform.career_assistant.dto.response.ResumeListItemResponse;
import com.careerplatform.career_assistant.entity.Resume;
import com.careerplatform.career_assistant.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    // get all resumes for a user — ordered by latest first
    List<Resume> findByUserOrderByUploadTimeDesc(User user);

    // count how many resumes a user has uploaded
    long countByUser(User user);

    /**
     * SECURITY: ownership-scoped lookup. Prefer this over findById() + a manual
     * owner check — the constraint travels with the query, so there is no way to
     * accidentally load another user's resume.
     */
    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    /**
     * 5.5 — kills the N+1 in ResumeProcessingService.getMyResumes().
     *
     * That method fetched the resume list and then called findByResume() once per row
     * inside a stream: 20 resumes meant 21 queries. This is one query, and it stays one
     * query at any list size.
     *
     * LEFT JOIN, not an inner join: a resume whose analysis failed or is still running
     * must still appear in the list, with null scores. The old orElse(...) branch did
     * exactly that and this preserves it.
     *
     * The constructor argument order must match ResumeListItemResponse's
     * \@AllArgsConstructor exactly — JPQL matches by position, not by name, and a wrong
     * order here fails at startup rather than at runtime, which is the good outcome.
     */
    @Query("""
            SELECT new com.careerplatform.career_assistant.dto.response.ResumeListItemResponse(
                r.id, a.id, r.fileName, r.uploadTime,
                a.overallScore, a.atsScore, a.interviewReadiness, a.experienceLevel)
            FROM Resume r
            LEFT JOIN AnalysisResult a ON a.resume = r
            WHERE r.user.id = :userId
            ORDER BY r.uploadTime DESC
            """)
    List<ResumeListItemResponse> findListForUser(@Param("userId") Long userId);

    /**
     * 5.4 — account deletion. Bulk DELETE rather than the derived deleteByUserId,
     * which would load every Resume entity first just to delete it.
     *
     * Returns the file paths before the rows go, so the caller can clean up disk.
     */
    @Query("SELECT r.filePath FROM Resume r WHERE r.user.id = :userId")
    List<String> findFilePathsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM Resume r WHERE r.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
