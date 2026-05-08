package com.careerplatform.career_assistant.entity;

import com.careerplatform.career_assistant.enums.SkillLevel;
import jakarta.persistence.*;
import lombok.*;
import java.util.List;

// one skill inside a phase
// e.g. React.js, Node.js, MongoDB
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "roadmap_skills")
public class RoadmapSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "phase_id", nullable = false)
    private RoadmapPhase phase;

    private String skillName;           // "React.js"
    private Integer skillOrder;         // ordering within phase
    private Integer estimatedDays;      // how long to learn this skill

    // what level user should reach for this skill
//    @Enumerated(EnumType.STRING)
    private String targetLevel;

    // what level user starts at — from their profile
//    @Enumerated(EnumType.STRING)
    private String startingLevel;

    // is skill already known by user at sufficient level
    // true = included for advancement, false = learning from scratch
    private Boolean isExistingSkill;

    // resources comma separated — "MDN Docs, freeCodeCamp, JavaScript.info"
    @Column(columnDefinition = "text")
    private String resources;

    // steps to learn this skill
    @OneToMany(mappedBy = "skill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC")
    private List<SkillStep> steps;

    // SKILL = learning skill, TOOL = tool/technology, PROJECT = project idea
    private String skillType; // "SKILL" | "TOOL" | "PROJECT"

    // only filled for PROJECT type skills
    @Column(columnDefinition = "text")
    private String projectDescription;

    @Column(columnDefinition = "json")
    private String techStack; // ["React", "Node.js", "MongoDB"]
}