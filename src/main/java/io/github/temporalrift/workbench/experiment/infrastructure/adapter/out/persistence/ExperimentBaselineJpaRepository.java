package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentBaselineJpaRepository
        extends JpaRepository<ExperimentBaselineEntity, ExperimentBaselineEntity.Key> {}
