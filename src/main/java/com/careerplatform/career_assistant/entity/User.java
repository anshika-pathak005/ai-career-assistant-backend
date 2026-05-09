package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column(length = 50)
    private String userCategory;

    @Column(name = "dream_job")
    private String dreamJob;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private List<String> industries;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private List<String> skills;

    @Column(name = "experience_years")
    private Integer experienceYears;

    @Column(name = "current_role")
    private String currentRole;

    @Column(name = "wizard_step", nullable = false)
    @Builder.Default
    private Integer wizardStep = 1;

    @Column(name = "is_profile_complete", nullable = false)
    @Builder.Default
    private Boolean isProfileComplete = false;

    @Column(name = "goal_type", length = 50)
    private String goalType;

    @Column(name = "project_level", length = 50)
    private String projectLevel;

    @Column(name = "work_preference", length = 50)
    private String workPreference;

    // skillLevels — {"React.js": "Beginner", "Java": "Intermediate"}
    @Column(columnDefinition = "json",name = "skill_levels")
    private String skillLevels;

    // weeklyHours — how many hours per week user can dedicate
    @Column(name = "weekly_hours")
    private Integer weeklyHours;

    // learningStyle — "Videos" / "Docs" / "Projects" / "Mixed"
    @Column(name = "learning_style")
    private String learningStyle;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── plan and generation tracking ──────────────────────────────────

    // FREE or PRO
    @Column(nullable = false)
    private String plan = "FREE";

    // how many roadmaps generated this month
    @Column(nullable = false)
    private Integer roadmapsGeneratedThisMonth = 0;

    // tracks which month the counter belongs to — "2026-05"
// when this changes → counter resets automatically
    @Column(nullable = false)
    private String currentMonthYear = "";
}