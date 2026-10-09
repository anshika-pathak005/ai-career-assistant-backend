package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

// @Data removed (5.1) — identity equals/hashCode and no relations in toString().
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
// 5.6: findByResume runs on every resume detail view; deleteAllByUserId on account delete.
@Table(name = "resume_analysis", indexes = {
        @Index(name = "idx_analysis_resume", columnList = "resume_id"),
        @Index(name = "idx_analysis_user", columnList = "user_id")
})
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // which resume this analysis belongs to
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    @ToString.Exclude
    private Resume resume;

    // which user — for quick lookup without joining resume
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    // ── scores ────────────────────────────────────────────────────
    private Integer overallScore;
    private Integer atsScore;
    private Integer technicalSkillsScore;
    private Integer projectQualityScore;
    private Integer formattingScore;

    // ── basic info extracted ──────────────────────────────────────
    // PII lifted straight out of the candidate's CV — kept out of toString() so it
    // cannot reach a log line through a stray "{}" placeholder.
    @ToString.Exclude private String fullName;
    @ToString.Exclude private String email;
    @ToString.Exclude private String phone;
    private String currentRole;
    private String experienceLevel;     // Entry / Mid / Senior
    private Double experienceYears;

    // ── dream job match ───────────────────────────────────────────
    @Column(columnDefinition = "text")
    private String dreamJobMatchSummary; // how well resume matches dream job

    private String interviewReadiness;   // Beginner / Moderate / Job Ready

    // ── analysis sections (JSON arrays) ──────────────────────────
    @Column(columnDefinition = "json")
    private String technicalSkills;      // skills found in resume

    @Column(columnDefinition = "json")
    private String strengths;            // exactly 3

    @Column(columnDefinition = "json")
    private String weaknesses;           // exactly 3

    @Column(columnDefinition = "json")
    private String missingSkills;        // 5-8 skills missing for dream job

    @Column(columnDefinition = "json")
    private String improvementSuggestions; // exactly 5

    @Column(columnDefinition = "json")
    private String projectSuggestions;   // exactly 3

    @Column(columnDefinition = "json")
    private String recommendedLearning;  // 4-5 technologies to learn next

    @Column(columnDefinition = "json")
    private String missingKeywords;      // ATS keywords missing

    // ── summary ───────────────────────────────────────────────────
    @Column(columnDefinition = "text")
    private String summary;              // 3 sentence overall summary

    // ── debug ─────────────────────────────────────────────────────
    @Column(columnDefinition = "text")
    @ToString.Exclude   // multi-kilobyte model output; never useful in a log line
    private String rawAiResponse;

    @Column(nullable = false, updatable = false)
    private LocalDateTime analysisDate;

    // one line verdict — "Strong frontend profile, weak on backend depth"
    @Column(columnDefinition = "text")
    private String resumeVerdict;

    // recruiter perspective — "A recruiter would see you as..."
    @Column(columnDefinition = "text")
    private String recruiterPerspective;

    // top 3 priority skills to learn first
    @Column(columnDefinition = "json")
    private String prioritySkills;

    // recommended roles based on resume strengths
    @Column(columnDefinition = "json")
    private String recommendedRoles;

    // certifications to get for dream job
    @Column(columnDefinition = "json")
    private String recommendedCertifications;

    // section wise analysis
    @Column(columnDefinition = "json")
    private String sectionAnalysis;

    // ATS specific suggestions — separate from general improvements
    @Column(columnDefinition = "json")
    private String atsSuggestions;

    @PrePersist
    protected void onCreate() {
        analysisDate = LocalDateTime.now();
    }

    /**
     * Identity-based equals/hashCode — see the note on User. @Data's field-based
     * version also touched the lazy relations below, which can fire a query or throw
     * LazyInitializationException from inside an equals() call.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AnalysisResult other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}