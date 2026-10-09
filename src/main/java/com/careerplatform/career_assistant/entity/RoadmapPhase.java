package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import java.util.List;

// one phase inside a roadmap
// e.g. Phase 1: Foundation, Phase 2: Core Skills
// @Data removed (5.1) — identity equals/hashCode and no relations in toString().
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "roadmap_phases")
public class RoadmapPhase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "roadmap_id", nullable = false)
    private CareerRoadmap roadmap;

    private String title;               // "Phase 1: Foundation"
    private String description;         // what this phase covers
    private Integer phaseOrder;         // 1, 2, 3 — for ordering
    private Integer estimatedWeeks;     // how long this phase takes

    // skills inside this phase
    @OneToMany(mappedBy = "phase", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("skillOrder ASC")
    // One query for the skills of up to 20 phases, not one query per phase.
    @BatchSize(size = 20)
    @ToString.Exclude
    private List<RoadmapSkill> skills;

    /** Identity-based equals/hashCode — see the note on User. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoadmapPhase other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}