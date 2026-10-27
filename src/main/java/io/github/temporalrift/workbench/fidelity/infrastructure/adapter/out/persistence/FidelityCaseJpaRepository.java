package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface FidelityCaseJpaRepository extends JpaRepository<FidelityCaseJpaEntity, UUID> {

    long countByCheckId(UUID checkId);

    Optional<FidelityCaseJpaEntity> findByCheckIdAndOrdinal(UUID checkId, int ordinal);

    List<FidelityCaseJpaEntity> findByCheckIdAndResultAndReproductionIdIsNullOrderByOrdinalAsc(
            UUID checkId, String result, Pageable page);

    List<FidelityCaseJpaEntity> findByCheckIdAndResultAndReproductionIdIsNotNullOrderByOrdinalAsc(
            UUID checkId, String result);

    List<FidelityCaseJpaEntity> findByRowIdInOrderByOrdinalAsc(Collection<UUID> rowIds);

    @Query("select c.result from FidelityCaseJpaEntity c where c.checkId = :checkId")
    List<String> results(UUID checkId);
}
