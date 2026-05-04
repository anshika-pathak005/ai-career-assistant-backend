package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

// one phase inside a roadmap
// e.g. Phase 1: Foundation, Phase 2: Core Skills
@Data
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
    private List<RoadmapSkill> skills;
}