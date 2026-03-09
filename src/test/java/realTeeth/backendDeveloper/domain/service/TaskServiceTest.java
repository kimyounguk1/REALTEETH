package realTeeth.backendDeveloper.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer; // ★ 추가됨
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import realTeeth.backendDeveloper.domain.dto.req.ClientReq;
import realTeeth.backendDeveloper.domain.dto.res.AiSuccessResDto;
import realTeeth.backendDeveloper.domain.dto.res.IssueKeyRes;
import realTeeth.backendDeveloper.domain.entity.Task;
import realTeeth.backendDeveloper.domain.entity.type.Status;
import realTeeth.backendDeveloper.domain.repository.TaskJpaRepository;
import realTeeth.backendDeveloper.global.error.CustomException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@AutoConfigureMockRestServiceServer
public class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    @MockitoBean private TaskJpaRepository taskJpaRepository;
    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean private TaskStatusService taskStatusService;

    @Autowired
    private MockRestServiceServer mockServer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 이전 테스트의 설정이 남아있을 수 있으니 초기화
        mockServer.reset();
    }

    @Test
    @DisplayName("api키 발급 후 kafka에 메시지를 send한다.")
    public void workProcess_Success() throws Exception {
        // 1. Given
        ClientReq req = new ClientReq("홍길동", "test@test.com", "http://image.url");
        Task mockTask = Task.builder().id(1L).build();
        IssueKeyRes mockKeyRes = new IssueKeyRes("fake-api-key");
        String responseJson = objectMapper.writeValueAsString(mockKeyRes);

        when(taskJpaRepository.save(any())).thenReturn(mockTask);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // 가짜 서버 응답 정의
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/mock/auth/issue-key")))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // 2. When
        taskService.workProcess(req);

        // 3. Then
        mockServer.verify(); // 가짜 서버 호출 검증
        verify(taskJpaRepository, times(1)).save(any());
        verify(taskStatusService, atLeastOnce()).updateStatus(anyLong(), any());
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("카프카 리스너가 동작하여 AI 서버의 PROCESSING 응답을 받고 상태를 업데이트 해야한다.")
    public void kafkaListener_Success() throws Exception {
        // 1. Given
        String imageUrl = "http://image.url";
        String apiKey = "fake-api-key";
        String taskId = "1";

        AiSuccessResDto mockAiResponse = new AiSuccessResDto("job-123", "PROCESSING");
        String responseJson = objectMapper.writeValueAsString(mockAiResponse);

        // AI 분석 서버(/mock/process)로 가는 요청을 가로채서 가짜 응답을 주도록 세팅
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/mock/process")))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // 2. When (실행: 카프카에서 메시지를 읽었다고 가정하고 리스너 직접 호출)
        taskService.kafkaListener(imageUrl, apiKey, taskId);

        // 3. Then (검증)
        mockServer.verify(); // AI 서버로 진짜 요청이 나갔는지 네트워크 검증

        // 상태 업데이트 서비스가 "job-123"을 들고 정확히 1번 호출되었는가?
        verify(taskStatusService, times(1)).updateToAiReqSuccess(1L, "job-123");
    }

    @Test
    @DisplayName("[실패]: 인증 키 API가 400 에러를 반환하면 상태를 FAILED로 바꾸고 예외를 던진다")
    public void workProcess_Fail_AuthApiError() throws Exception {
        // Given (준비)
        ClientReq req = new ClientReq("홍길동", "test@test.com", "http://image.url");
        Task mockTask = Task.builder().id(1L).build();
        when(taskJpaRepository.save(any())).thenReturn(mockTask);

        // 가짜 에러 응답 JSON (KeyErrorResDto의 구조에 맞게 작성: {"detail": "..."})
        String errorResponseJson = "{\"detail\": \"유효하지 않은 이메일입니다.\"}";

        // 이번에는 가짜 서버가 성공(200)이 아니라 에러(400 Bad Request)를 던지게 설정
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/mock/auth/issue-key")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponseJson));

        // When & Then (실행 및 검증)
        // CustomException이 발생해야 통과
        assertThrows(CustomException.class, () -> {
            taskService.workProcess(req);
        });

        mockServer.verify(); // 가짜 서버 호출 검증

        // 예외가 터졌으니 상태 업데이트 서비스가 실패(updateToFailed)로 정확히 1번 호출되어야 함.
        verify(taskStatusService, times(1)).updateToFailed(eq(1L), anyString());

        // 인증이 실패했으므로 카프카 전송은 실행되지 않아야 함.
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }


    @Test
    @DisplayName("[실패]: AI 서버 API가 에러를 반환하면 리스너는 예외를 던지지 않고 상태만 FAILED로 바꾼다")
    public void kafkaListener_Fail_AiApiError() throws Exception {
        // 1. Given (준비)
        String imageUrl = "http://image.url";
        String apiKey = "fake-api-key";
        String taskId = "1";

        // 가짜 에러 응답 JSON (AiFailResDto의 구조에 맞게 작성)
        String errorResponseJson = "{\"detail\": \"이미지 분석 실패\"}";

        // AI 서버가 에러(500 Internal Server Error)를 던지도록 설정
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/mock/process")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponseJson));

        // 2. When (실행)
        // 리스너는 @KafkaListener 이기 때문에 내부에서 try-catch로 예외를 잡고 밖으로 던지지 않습니다.
        taskService.kafkaListener(imageUrl, apiKey, taskId);

        // 3. Then (검증)
        mockServer.verify();

        // 상태가 실패(FAILED)로 1번 업데이트 되어야함.
        verify(taskStatusService, times(1)).updateToFailed(eq(1L), anyString());

        // 실패했으므로 성공 업데이트(updateToAiReqSuccess)는 절대 호출되면 안됨.
        verify(taskStatusService, never()).updateToAiReqSuccess(anyLong(), anyString());
    }

    @Test
    @DisplayName("상태 조회 성공: 존재하는 jobId로 조회 시 해당 Task의 상태(문자열)를 반환한다")
    public void getJobStatus_Success() {
        // 1. Given (준비)
        String jobId = "job-123";

        // DB에 PROCESSING 상태를 가진 가짜 Task 객체 생성
        Task mockTask = Task.builder()
                .status(Status.PROCESSING) // 현재 상태
                .build();

        // DB의 findByJobId가 호출되면 위에서 만든 mockTask를 담은 Optional을 반환
        when(taskJpaRepository.findByJobId(jobId)).thenReturn(Optional.of(mockTask));

        // 2. When (실행)
        String resultStatus = taskService.getJobStatus(jobId);

        // 3. Then (검증)
        // 반환된 문자열이 "PROCESSING"이 맞는지 확인
        assertEquals("PROCESSING", resultStatus);

        // DB 조회가 정확히 1번 실행되었는지 확인
        verify(taskJpaRepository, times(1)).findByJobId(jobId);
    }

    @Test
    @DisplayName("[실패]: 이미 처리 중인 중복 요청이 들어오면 작업을 중단하고 예외를 발생시킨다")
    public void workProcess_Fail_DuplicateRequest() throws Exception {
        // 1. Given (준비)
        ClientReq req = new ClientReq("홍길동", "test@test.com", "http://image.url");

        // exist가 true를 반환하게 설정
        when(taskJpaRepository.existsByEmailAndImageUrlAndStatusIn(anyString(), anyString(), anyList()))
                .thenReturn(true);

        // 2. When & Then (실행 및 예외 검증)
        // 중복 요청 예외가 발생 해야 함.
        assertThrows(CustomException.class, () -> {
            taskService.workProcess(req);
        });

        // 3. Then
        // 아래 작업들은 실행되면 안됨
        verify(taskJpaRepository, never()).save(any());
        verify(taskStatusService, never()).updateStatus(anyLong(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

    }

}