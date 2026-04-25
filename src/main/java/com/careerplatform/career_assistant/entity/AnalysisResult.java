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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    // ── contact info ──────────────────────────────
    private String fullName;
    private String email;
    private String phone;

    // ── scores ────────────────────────────────────
    private Integer overallScore;
    private Integer atsScore;

    // ── experience ────────────────────────────────
    private String experienceLevel;
    private Double totalExperienceYears;
    private String currentRole;

    // ── skills (JSON arrays) ──────────────────────
    @Column(columnDefinition = "json")
    private String technicalSkills;

    @Column(columnDefinition = "json")
    private String softSkills;

    @Column(columnDefinition = "json")
    private String missingSkills;

    @Column(columnDefinition = "json")
    private String missingKeywords;

    // ── analysis ──────────────────────────────────
    @Column(columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "json")
    private String strengths;

    @Column(columnDefinition = "json")
    private String improvements;

    // ── career guidance (NEW) ─────────────────────
    @Column(columnDefinition = "json")
    private String recommendedRoles;

    @Column(columnDefinition = "json")
    private String learningRoadmap;

    @Column(columnDefinition = "json")
    private String projectSuggestions;

    @Column(columnDefinition = "text")
    private String careerAdvice;

    // ── debug ─────────────────────────────────────
    @Column(columnDefinition = "text")
    private String rawAiResponse;

    @Column(nullable = false, updatable = false)
    private LocalDateTime analysisDate;

    @PrePersist
    protected void onCreate() {
        analysisDate = LocalDateTime.now();
    }
}