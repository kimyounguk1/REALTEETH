package realTeeth.backendDeveloper.domain.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import realTeeth.backendDeveloper.domain.entity.Task;
import realTeeth.backendDeveloper.domain.entity.type.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class TaskJpaRepositoryTest {

    @Autowired
    private TaskJpaRepository taskJpaRepository;

    @Test
    @DisplayName("데이터가 저장된 후 exists 쿼리는 true를 반환해야함")
    void exists_True_Test() {
        // Given
        Task build = Task.builder().email("test@test.com").imageUrl("example.com").status(Status.PROCESSING).build();
        // When
        taskJpaRepository.saveAndFlush(build);
        // Then
        boolean result = taskJpaRepository.existsByEmailAndImageUrlAndStatusIn("test@test.com", "example.com", List.of(Status.PENDING, Status.PROCESSING));

        assertTrue(result);
    }
}