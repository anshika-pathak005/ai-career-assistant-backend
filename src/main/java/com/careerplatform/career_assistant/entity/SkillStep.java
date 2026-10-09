package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.*;

// one step inside a skill
// e.g. "Learn JSX and component basics"
// @Data removed (5.1) — identity equals/hashCode and no relations in toString().
@Getter
@Setter
@ToString
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
    @ToString.Exclude
    private RoadmapSkill skill;

    private String title;               // "Learn JSX basics"
    private String description;         // what to do in this step
    private Integer stepOrder;          // ordering within skill
    private Integer estimatedHours;     // time to complete

    // user progress on this step
    private Boolean completed;
    private Boolean skipped;

    @PrePersist
    protected void onCreate() {
        completed = false;
        skipped = false;
    }

    /** Identity-based equals/hashCode — see the note on User. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SkillStep other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}