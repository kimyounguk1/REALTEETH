package realTeeth.backendDeveloper.domain.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import realTeeth.backendDeveloper.domain.entity.Outbox;
import realTeeth.backendDeveloper.domain.entity.type.Status;
import realTeeth.backendDeveloper.domain.repository.OutboxJpaRepository;
import realTeeth.backendDeveloper.domain.service.TaskStatusService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {
    private final OutboxJpaRepository outboxJpaRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TaskStatusService taskStatusService;

    @Scheduled(fixedDelay = 1000) // 1초마다 실행
    public void relay() {
        // [STEP 1] 아직 시도조차 안 한(processed=false) 메시지들만 가져옴
        List<Outbox> targets = outboxJpaRepository.findByProcessedFalse();

        for (Outbox outbox : targets) {
            try {
                // 별도의 새 트랜잭션을 열어서 DB 먼저 확정(Commit) 지음
                markAsAttempted(outbox.getId());

                // Kafka로 전송 (재시도 없이 1회성)
                ProducerRecord<String, String> record = new ProducerRecord<>(outbox.getTopic(), outbox.getPayload());
                record.headers().add("X-API-KEY", outbox.getApiKey().getBytes(StandardCharsets.UTF_8));
                record.headers().add("TASK-ID", String.valueOf(outbox.getTaskId()).getBytes(StandardCharsets.UTF_8));

                kafkaTemplate.send(record).get(3, TimeUnit.SECONDS);
                log.info("Kafka 전송 완료 (1회 보장) - TaskID: {}", outbox.getTaskId());

            } catch (Exception e) {
                // (Saga)
                log.error("Kafka 전송 실패 - TaskID: {}, 사유: {}", outbox.getTaskId(), e.getMessage());
                taskStatusService.updateToFailed(outbox.getTaskId(), "전송 중 오류: " + e.getMessage());
            }
        }
    }

    // REQUIRES_NEW: 부모 트랜잭션과 상관없이 이 메서드만 즉시 DB에 커밋함
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsAttempted(Long id) {
        Outbox outbox = outboxJpaRepository.findById(id).orElseThrow();
        outbox.markProcessed(); // processed = true 로 변경
        taskStatusService.updateStatus(outbox.getTaskId(), Status.PROCESSING);
    }
}