package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "resume_analysis")
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // which resume this analysis belongs to
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    // which user — for quick lookup without joining resume
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // ── scores ────────────────────────────────────────────────────
    private Integer overallScore;
    private Integer atsScore;
    private Integer technicalSkillsScore;
    private Integer projectQualityScore;
    private Integer formattingScore;

    // ── basic info extracted ──────────────────────────────────────
    private String fullName;
    private String email;
    private String phone;
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
    private String rawAiResponse;

    @Column(nullable = false, updatable = false)
    private LocalDateTime analysisDate;

    @PrePersist
    protected void onCreate() {
        analysisDate = LocalDateTime.now();
    }
}