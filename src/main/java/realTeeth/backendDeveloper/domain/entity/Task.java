package realTeeth.backendDeveloper.domain.entity;

import realTeeth.backendDeveloper.domain.entity.type.Status;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "task", indexes = {
        @Index(name = "idx_status_update_at", columnList = "status, updated_at")
})
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    //job_id는 중복이거나 null일 수 없음
    @Column(unique = true)
    private String jobId;

    //image_url은 null일 수 없음
    @Column(nullable = false)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String errorMessage;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

}
