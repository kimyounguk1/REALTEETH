package realTeeth.backendDeveloper.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String topic;      // image.send
    private String payload;    // imageUrl
    private String apiKey;     // 헤더에 넣을 키
    private Long taskId;       // 대상 Task ID

    private boolean processed = false; // 전송 완료 여부
    private LocalDateTime createdAt = LocalDateTime.now();

    public void markProcessed() { this.processed = true; }
}
