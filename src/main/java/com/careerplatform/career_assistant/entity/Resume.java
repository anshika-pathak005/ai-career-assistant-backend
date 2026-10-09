package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

// @Data removed (5.1) — identity equals/hashCode and no relations in toString().
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
// 5.6: backs findByUserOrderByUploadTimeDesc and the new list projection query.
@Table(name = "resumes", indexes = {
        @Index(name = "idx_resume_user_uploaded", columnList = "user_id, upload_time")
})
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // which user uploaded this resume
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(nullable = false)
    private String fileName;       // original file name

    @Column(nullable = false)
    private String filePath;       // where it's stored on disk

    private Long fileSize;         // in bytes

    private String fileType;       // application/pdf etc.

    @Column(nullable = false, updatable = false)
    private LocalDateTime uploadTime;

    @PrePersist
    protected void onCreate() {
        uploadTime = LocalDateTime.now();
    }

    /**
     * Identity-based equals/hashCode — see the note on User. @Data's field-based
     * version also touched the lazy relations below, which can fire a query or throw
     * LazyInitializationException from inside an equals() call.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Resume other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}