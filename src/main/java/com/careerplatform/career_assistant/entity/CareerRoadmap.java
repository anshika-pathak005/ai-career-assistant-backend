package com.careerplatform.career_assistant.entity;

import com.careerplatform.career_assistant.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

// one user can have multiple roadmaps
// only one is ACTIVE at a time — rest are ARCHIVED
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "career_roadmaps")
public class CareerRoadmap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // snapshot of dream job at generation time
    // stored separately so archiving works correctly even if user changes dream job later
    @Column(nullable = false)
    private String dreamJobSnapshot;

    // overall roadmap status
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoadmapStatus status;

    // total estimated weeks to complete roadmap
    private Integer totalEstimatedWeeks;

    // overall summary of the roadmap from AI
    @Column(columnDefinition = "text")
    private String summary;

    // raw AI response — kept for debugging
    @Column(columnDefinition = "text")
    private String rawAiResponse;

    // when roadmap was generated
    @Column(nullable = false, updatable = false)
    private LocalDateTime generatedAt;

    // when roadmap was archived — null if still active
    private LocalDateTime archivedAt;

    // phases inside this roadmap — cascade means phases deleted when roadmap deleted
    @OneToMany(mappedBy = "roadmap", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("phaseOrder ASC")
    private java.util.List<RoadmapPhase> phases;

    @PrePersist
    protected void onCreate() {
        generatedAt = LocalDateTime.now();
    }
}