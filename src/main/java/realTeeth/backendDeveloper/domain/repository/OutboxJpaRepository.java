package realTeeth.backendDeveloper.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import realTeeth.backendDeveloper.domain.entity.Outbox;

import java.util.List;

public interface OutboxJpaRepository extends JpaRepository<Outbox, Long> {

    List<Outbox> findByProcessedFalse();

}