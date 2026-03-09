package realTeeth.backendDeveloper.domain.service;

import org.springframework.http.ResponseEntity;
import realTeeth.backendDeveloper.domain.dto.req.ClientReq;
import realTeeth.backendDeveloper.domain.dto.res.AiFailResDto;
import realTeeth.backendDeveloper.domain.dto.res.AiSuccessResDto;
import realTeeth.backendDeveloper.domain.dto.res.IssueKeyRes;
import realTeeth.backendDeveloper.domain.dto.res.KeyErrorResDto;
import realTeeth.backendDeveloper.domain.entity.Task;
import realTeeth.backendDeveloper.domain.entity.type.Status;
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

    public void workProcess(ClientReq clientReq){

        duplicateCheck(clientReq);

        //새로운 요청을 받을시 task를 생성
        Task task = makeTask(clientReq);
        Long taskId = task.getId();

        String issueKey;

        try{
            //이슈키 생성
            IssueKeyRes issueKeyRes = getIssueKeyProcess(clientReq.getCandidateName(), clientReq.getEmail(), taskId);
            issueKey = issueKeyRes.getApiKey();
            //이미지 처리 실행
            sendImage(issueKey, clientReq.getImageUrl(), taskId);
        } catch (Exception e) {
            taskStatusService.updateToFailed(taskId, e.getMessage());
            throw e;
        }



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

    @KafkaListener(
            topics = "image.send",
            groupId = "image-send-group"
    )
    //상태의 명확성을 위해 재시도 하지 않음.
    //ai 서버로 확실히 요청을 보냈는지는 알 수 없는 지점이 있음, ai서버에 요청이 갔는지 안갔는지 확인 할 수 있는 수단이 없음.
    public void kafkaListener (@Payload String imageUrl, @Header("X-API-KEY") String key, @Header("TASK-ID") String taskId) {

        Long longTaskId = Long.parseLong(taskId);
        log.info("이미지 처리 요청 시작 : {}", imageUrl);
        log.info("가상 스레드 여부 : {}", Thread.currentThread().isVirtual());
        try{
            AiSuccessResDto aiResponse = restClient.post()
                    .uri("https://dev.realteeth.ai/mock/process")
                    .header("X-API-KEY", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("imageUrl", imageUrl))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req,res)->{
                        AiFailResDto errorBody = objectMapper.readValue(res.getBody(), AiFailResDto.class);
                        log.error("AI API 호출 도중 HTTP 에러 발생 : {}, 에러 원문 : {}", res.getStatusCode(), errorBody.detail());
                        //외부 AI 서버 예외 통일, 자세한 사항은 message로 식별
                        throw new CustomException(ErrorCode.AI_PROCESS_FAIL, errorBody.detail().toString());
                    })
                    .body(AiSuccessResDto.class);
            taskStatusService.updateToAiReqSuccess(longTaskId, aiResponse.jobId());

            log.info("이미지 분석 요청 성공 : jobID={}, status={}", aiResponse.jobId(), aiResponse.status());
        }catch (CustomException e){
            taskStatusService.updateToFailed(longTaskId, e.getMessage());
        }catch (Exception e){
            taskStatusService.updateToFailed(longTaskId, e.getMessage());
            // 네트워크 타임아웃, 커넥션 거부 등 "기술적 장애" 처리
            Throwable cause = (e instanceof ResourceAccessException) ? e.getCause() : e;

            if (cause instanceof java.net.http.HttpTimeoutException) {
                log.error("이미지 분석 요청 타임아웃 발생", e);
            }

            if (cause instanceof java.net.ConnectException || e instanceof ResourceAccessException) {
                log.error("AI 서버 연결 실패", e);
            }

            // 그 외 예상치 못한 내부 런타임 에러
            log.error("이미지 분석 요청 중 알 수 없는 예외 발생", e);
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
