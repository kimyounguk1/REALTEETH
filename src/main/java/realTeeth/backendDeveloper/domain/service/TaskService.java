package realTeeth.backendDeveloper.domain.service;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import realTeeth.backendDeveloper.domain.dto.req.ClientReq;
import realTeeth.backendDeveloper.domain.dto.res.AiFailResDto;
import realTeeth.backendDeveloper.domain.dto.res.AiSuccessResDto;
import realTeeth.backendDeveloper.domain.dto.res.IssueKeyRes;
import realTeeth.backendDeveloper.domain.dto.res.KeyErrorResDto;
import realTeeth.backendDeveloper.domain.entity.Outbox;
import realTeeth.backendDeveloper.domain.entity.Task;
import realTeeth.backendDeveloper.domain.entity.type.Status;
import realTeeth.backendDeveloper.domain.repository.OutboxJpaRepository;
import realTeeth.backendDeveloper.domain.repository.TaskJpaRepository;
import realTeeth.backendDeveloper.global.error.CustomException;
import realTeeth.backendDeveloper.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.View;



import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;


@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskJpaRepository taskJpaRepository;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TaskStatusService taskStatusService;
    private final OutboxJpaRepository outboxJpaRepository;

    public void workProcess(ClientReq clientReq) {
        duplicateCheck(clientReq);

        // 초기 Task 생성 (PENDING)
        Task task = taskJpaRepository.save(Task.builder()
                .email(clientReq.getEmail())
                .imageUrl(clientReq.getImageUrl())
                .status(Status.PENDING)
                .build());

        try {
            // 외부 API 호출 (Issue Key) - 트랜잭션 외부에서 실행 (커넥션 점유 방지)
            IssueKeyRes issueKeyRes = getIssueKeyProcess(clientReq.getCandidateName(), clientReq.getEmail(), task.getId());

            // 상태 변경과 Outbox 저장을 하나의 트랜잭션으로 묶음 (원자성)
            saveOutboxAndTransition(task, issueKeyRes.getApiKey());

        } catch (Exception e) {
            taskStatusService.updateToFailed(task.getId(), "초기화 실패: " + e.getMessage());
            throw e;
        }
    }

    @Transactional
    public void saveOutboxAndTransition(Task task, String apiKey) {
        //상태 변경과 메시지 요청을 하나로 묶음(outbox)
        //우리 DB의 상태와 kafka의 상태가 항상 일치
        taskStatusService.updateStatus(task.getId(), Status.PROCESSING);

        outboxJpaRepository.save(Outbox.builder()
                .topic("image.send")
                .payload(task.getImageUrl())
                .apiKey(apiKey)
                .taskId(task.getId())
                .processed(false)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private void duplicateCheck(ClientReq clientReq) {
        boolean isDuplicate = taskJpaRepository.existsByEmailAndImageUrlAndStatusIn(
                clientReq.getEmail(),
                clientReq.getImageUrl(),
                List.of(Status.PENDING, Status.PROCESSING)
        );

        if (isDuplicate) {
            // 중복 방지 로직
            throw new CustomException(ErrorCode.DUPLICATE_REQUEST, "이미 처리 중인 동일한 작업이 있습니다.");
        }
    }

    private Task makeTask (ClientReq clientReq) {
        Task newTask = Task.builder()
                .imageUrl(clientReq.getImageUrl())
                .email(clientReq.getEmail())
                .status(Status.PENDING)
                .build();

        return taskJpaRepository.save(newTask);
    }

    public void sendImage (String apiKey, String imageUrl, Long taskId) {

        // 1. 레코드 생성 (데이터는 순수하게 imageUrl 문자열만 담음)
        ProducerRecord<String, String> record = new ProducerRecord<>("image.send", imageUrl);

        // 2. 헤더에 API KEY 추가
        record.headers().add("X-API-KEY", apiKey.getBytes(StandardCharsets.UTF_8));
        record.headers().add("TASK-ID", String.valueOf(taskId).getBytes(StandardCharsets.UTF_8));
        try {
            kafkaTemplate.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "카프카 전송 중 스레드 중단 : " + e.getMessage());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("카프카 전송 물리적 오류 : ", cause);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "카프카 전송 실패 : " + cause.getMessage());
        }

    }

    @KafkaListener(topics = "image.send", groupId = "image-send-group")
    public void kafkaListener(@Payload String imageUrl, @Header("X-API-KEY") String key, @Header("TASK-ID") String taskId) {
        Long longTaskId = Long.parseLong(taskId);

        try {
            AiSuccessResDto res = restClient.post()
                    .uri("https://dev.realteeth.ai/mock/process")
                    .header("X-API-KEY", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("imageUrl", imageUrl))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw new CustomException(ErrorCode.AI_PROCESS_FAIL, "AI 서버 응답 에러");
                    })
                    .body(AiSuccessResDto.class);

            // 성공 시 상태 업데이트
            taskStatusService.updateToAiReqSuccess(longTaskId, res.jobId());
        } catch (Exception e) {
            // [Saga 보상 로직] 명시적 실패 시 DB 상태를 즉시 FAILED로 변경
            log.error("AI 처리 중 에러 발생, 상태를 FAILED로 변경합니다. TaskID: {}", longTaskId);
            taskStatusService.updateToFailed(longTaskId, e.getMessage());
        }
    }

    public IssueKeyRes getIssueKeyProcess(String candidateName, String email, Long taskId) {

        log.info("가상 스레드 여부 : {}", Thread.currentThread().isVirtual());
        try {
            IssueKeyRes issueKey = restClient.post()
                    .uri("https://dev.realteeth.ai/mock/auth/issue-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                            Map.of(
                                    "candidateName", candidateName,
                                    "email", email
                            )
                    )
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        KeyErrorResDto errorBody = objectMapper.readValue(res.getBody(), KeyErrorResDto.class);
                        log.error("API 호출 중 HTTP 에러 발생 : {}, 에러 원문 : {}", res.getStatusCode(), errorBody);
                        //외부 API-Key 서버 예외 통일
                        throw new CustomException(ErrorCode.API_KEY_PROCESS_FAIL, errorBody.detail().toString());
                    })
                    .body(IssueKeyRes.class);
            taskStatusService.updateStatus(taskId, Status.PROCESSING);
            return issueKey;
        }catch (CustomException e){
            throw e;
        } catch (Exception e) {

            // 네트워크 타임아웃, 커넥션 거부 등 "기술적 장애" 처리
            Throwable cause = (e instanceof ResourceAccessException) ? e.getCause() : e;

            if (cause instanceof java.net.http.HttpTimeoutException) {
                log.error("인증 키 요청 타임아웃 발생", e);
                throw new CustomException(ErrorCode.GATEWAY_TIMEOUT, "인증 서버 응답이 지연되고 있습니다.");
            }

            if (cause instanceof java.net.ConnectException || e instanceof ResourceAccessException) {
                log.error("인증 서버 연결 거부", e);
                throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE, "현재 인증 서버를 사용할 수 없습니다.");
            }

            // 그 외 예상치 못한 내부 런타임 에러
            log.error("인증 키 발급 중 알 수 없는 예외 발생", e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "인증 처리 중 시스템 오류가 발생했습니다.");
        }

    }


    public String getJobStatus(String jobId) {

        Task task = taskJpaRepository.findByJobId(jobId).orElseThrow(() -> new CustomException(ErrorCode.NON_EXIST_TASK, "존재하지 않는 테스크입니다."));

        Status status = task.getStatus();

        return status.name();
    }
}
