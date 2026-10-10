package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface RunProgressJpaRepository extends Repository<RunProgressJpaEntity, UUID> {

    List<RunProgressJpaEntity> findAllByRunIdIn(Collection<UUID> runIds);
}
