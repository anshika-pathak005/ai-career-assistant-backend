package com.careerplatform.career_assistant.entity;

import com.careerplatform.career_assistant.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import java.time.LocalDateTime;

// one user can have multiple roadmaps
// only one is ACTIVE at a time — rest are ARCHIVED
// @Data removed (5.1) — identity equals/hashCode and no relations in toString().
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
// 5.6: findByUserAndStatus is the single hottest query in the app — dashboard,
// profile, roadmap screen and archiving all run it.
@Table(name = "career_roadmaps", indexes = {
        @Index(name = "idx_roadmap_user_status", columnList = "user_id, status"),
        @Index(name = "idx_roadmap_user_generated", columnList = "user_id, generated_at")
})
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
    // Loads this collection for up to 20 roadmaps in a single query instead of one
    // query per roadmap. Matters on the history screen, which maps N roadmaps.
    @BatchSize(size = 20)
    private java.util.List<RoadmapPhase> phases;


    /** Identity-based equals/hashCode — see the note on User. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CareerRoadmap other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @PrePersist
    protected void onCreate() {
        generatedAt = LocalDateTime.now();
    }
}