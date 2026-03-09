package realTeeth.backendDeveloper.domain.controller;

import realTeeth.backendDeveloper.domain.dto.req.ClientReq;
import realTeeth.backendDeveloper.domain.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Slf4j
public class TaskController {

    private final TaskService taskService;

    @Operation(summary = "key 발급 및 이미지 분석 실행", description = "참여자 이름, 이메일, 이미지 url을 이용해 작업")
    @PostMapping("/work")
    public ResponseEntity<String> getIssueKey(@RequestBody @Valid ClientReq issueKeyReq) {

        taskService.workProcess(issueKeyReq);

        return ResponseEntity.ok("이미지 처리 요청 완료");
    }

    @Operation(summary = "진행 상태 조회")
    @GetMapping("/check")
    public ResponseEntity<String> checkIssueKey(@RequestParam("jobId") String jobId) {

        String status =  taskService.getJobStatus(jobId);

        return ResponseEntity.ok(status);

    }

}
