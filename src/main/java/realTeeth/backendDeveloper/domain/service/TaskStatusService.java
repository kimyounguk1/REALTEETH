package realTeeth.backendDeveloper.domain.service;

import realTeeth.backendDeveloper.domain.entity.Task;
import realTeeth.backendDeveloper.domain.entity.type.Status;
import realTeeth.backendDeveloper.domain.repository.TaskJpaRepository;
import realTeeth.backendDeveloper.global.error.CustomException;
import realTeeth.backendDeveloper.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TaskStatusService {

    private final TaskJpaRepository taskJpaRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus (Long taskId, Status status){

        Task task = taskJpaRepository.findById(taskId).orElseThrow(() -> new CustomException(ErrorCode.NON_EXIST_TASK, "Task가 존재하지 않습니다."));
        task.setStatus(status);

    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateToFailed (Long taskId, String reason){

        Task task = taskJpaRepository.findById(taskId).orElseThrow(() -> new CustomException(ErrorCode.NON_EXIST_TASK, "Task가 존재하지 않습니다."));
        task.setStatus(Status.FAILED);
        task.setErrorMessage(reason);

    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateToAiReqSuccess (Long taskId, String jobId){

        Task task = taskJpaRepository.findById(taskId).orElseThrow(() -> new CustomException(ErrorCode.NON_EXIST_TASK, "Task가 존재하지 않습니다."));
        task.setStatus(Status.COMPLETED);
        task.setJobId(jobId);
    }
}
