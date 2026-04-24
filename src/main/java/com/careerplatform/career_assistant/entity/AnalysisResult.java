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

    // one analysis per resume
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    // contact info extracted by AI
    private String fullName;
    private String email;
    private String phone;

    // scores
    private Integer overallScore;   // 0-100
    private Integer atsScore;       // 0-100

    // experience
    private String experienceLevel;        // Entry/Mid/Senior
    private Double totalExperienceYears;
    private String currentRole;

    // JSON columns — List<String> stored as JSON
    @Column(columnDefinition = "json")
    private String technicalSkills;

    @Column(columnDefinition = "json")
    private String softSkills;

    @Column(columnDefinition = "json")
    private String missingSkills;

    @Column(columnDefinition = "json")
    private String missingKeywords;

    // analysis
    @Column(columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "json")
    private String strengths;

    @Column(columnDefinition = "json")
    private String improvements;

    // raw AI response — for debugging
    @Column(columnDefinition = "text")
    private String rawAiResponse;

    @Column(nullable = false, updatable = false)
    private LocalDateTime analysisDate;

    @PrePersist
    protected void onCreate() {
        analysisDate = LocalDateTime.now();
    }
}