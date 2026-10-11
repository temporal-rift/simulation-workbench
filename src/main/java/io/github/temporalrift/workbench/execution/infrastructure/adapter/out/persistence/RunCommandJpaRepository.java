package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RunCommandJpaRepository extends JpaRepository<RunCommandJpaEntity, UUID> {}
