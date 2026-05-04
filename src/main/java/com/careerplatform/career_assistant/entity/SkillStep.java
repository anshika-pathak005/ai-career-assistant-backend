package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.*;

// one step inside a skill
// e.g. "Learn JSX and component basics"
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "skill_steps")
public class SkillStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false)
    private RoadmapSkill skill;

    private String title;               // "Learn JSX basics"
    private String description;         // what to do in this step
    private Integer stepOrder;          // ordering within skill
    private Integer estimatedHours;     // time to complete

    // user progress on this step
    private Boolean completed;

    @PrePersist
    protected void onCreate() {
        completed = false;
    }
}