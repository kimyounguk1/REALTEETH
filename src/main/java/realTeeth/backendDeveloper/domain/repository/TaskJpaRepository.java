package realTeeth.backendDeveloper.domain.repository;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import realTeeth.backendDeveloper.domain.entity.Task;
import realTeeth.backendDeveloper.domain.entity.type.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskJpaRepository extends JpaRepository<Task, Long> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Task t SET t.status = :failedStatus, t.errorMessage = :reason " +
            "WHERE t.status IN :targetStatuses " +
            "AND t.updatedAt < :threshold")
    int bulkFailOldTasks(
            @Param("threshold") LocalDateTime threshold,
            @Param("reason") String reason,
            @Param("failedStatus") Status failedStatus,       // Status.FAILED 전달
            @Param("targetStatuses") List<Status> targetStatuses // List.of(Status.PENDING, Status.PROCESSING) 전달
    );

    Optional<Task> findByJobId(String jobId);

    boolean existsByEmailAndImageUrlAndStatusIn(String email, String imageUrl, List<Status> status);
}
