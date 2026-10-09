package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperimentJpaRepository extends JpaRepository<ExperimentEntity, UUID> {

    @Query(
            value = "SELECT * FROM experiment ORDER BY created_at DESC, experiment_id DESC LIMIT :limit OFFSET :offset",
            nativeQuery = true)
    List<ExperimentEntity> findNewest(@Param("limit") int limit, @Param("offset") int offset);
}
