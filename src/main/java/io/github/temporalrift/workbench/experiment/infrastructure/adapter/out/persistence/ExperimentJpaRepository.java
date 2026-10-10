package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentJpaRepository extends JpaRepository<ExperimentEntity, UUID> {

    List<ExperimentEntity> findAllByOrderByCreatedAtDescExperimentIdDesc(Pageable page);
}
