package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentJpaRepository extends JpaRepository<ExperimentEntity, UUID> {}
