package realTeeth.backendDeveloper.domain.schedule;

import realTeeth.backendDeveloper.domain.entity.type.Status;
import realTeeth.backendDeveloper.domain.repository.TaskJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TaskRecoveryScheduler {

    private final TaskJpaRepository taskJpaRepository;

    //1시간 마다 스케줄러로 좀비 태스크 정리(실패 처리)
    @Scheduled(cron = "0 10 * * * *")
    @Transactional
    public void recovery() {

        LocalDateTime localDateTime = LocalDateTime.now().plusMinutes(10);

        List<Status> targets = List.of(Status.PENDING, Status.PROCESSING);

        int updatedCount = taskJpaRepository.bulkFailOldTasks(localDateTime, "시스템 타임아웃 : 10분 경과", Status.FAILED, targets);

        if (updatedCount > 0) {
            log.warn("{}건의 좀비 태스크를 FAILED로 정리했습니다.", updatedCount);
        }
    }

}
