package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import io.github.temporalrift.workbench.fidelity.domain.CheckClaim;
import io.github.temporalrift.workbench.fidelity.domain.CheckCreation;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCaseResult;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCounts;
import io.github.temporalrift.workbench.fidelity.domain.FidelityEnvironment;
import io.github.temporalrift.workbench.fidelity.domain.FidelityFailure;
import io.github.temporalrift.workbench.fidelity.domain.FidelityVariant;
import io.github.temporalrift.workbench.fidelity.domain.FirstDivergence;
import io.github.temporalrift.workbench.fidelity.domain.SourceRevisions;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;
import io.github.temporalrift.workbench.shared.Page;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.OffsetLimit;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.StoredJson;

/** Fidelity checks and their cases in the workbench database. */
@Component
public class FidelityRepositoryAdapter implements FidelityRepository {

    private static final List<String> OPEN_STATES = List.of("QUEUED", "RUNNING");
    private static final String PENDING = "PENDING";

    private final FidelityCheckJpaRepository checks;
    private final FidelityCaseJpaRepository cases;
    private final FidelityCaseQueries caseQueries;
    private final InsertOnce insertOnce;
    private final StoredJson json;

    FidelityRepositoryAdapter(
            FidelityCheckJpaRepository checks,
            FidelityCaseJpaRepository cases,
            FidelityCaseQueries caseQueries,
            InsertOnce insertOnce,
            StoredJson json) {
        this.checks = checks;
        this.cases = cases;
        this.caseQueries = caseQueries;
        this.insertOnce = insertOnce;
        this.json = json;
    }

    @Override
    public CheckCreation create(UUID idempotencyKey, String requestHash, FidelityCheck queued) {
        var claimed = findClaim(idempotencyKey);
        if (claimed.isPresent()) {
            return new CheckCreation.Existing(claimed.get());
        }
        if (open().isPresent()) {
            return new CheckCreation.Active();
        }
        var revisions = queued.simulator().sourceRevisions();
        var stored = insertOnce.insert(
                () -> checks.saveAndFlush(new FidelityCheckJpaEntity(new FidelityCheckJpaEntity.Accepted(
                        queued.id(),
                        idempotencyKey,
                        requestHash,
                        queued.seed(),
                        queued.laneId(),
                        json.write(queued.simulator()),
                        json.write(queued.lane()),
                        json.write(queued.variants()),
                        revisions.gameService(),
                        revisions.timelineService(),
                        revisions.readService(),
                        queued.createdAt()))));
        if (stored) {
            return new CheckCreation.Created();
        }
        return findClaim(idempotencyKey)
                .<CheckCreation>map(CheckCreation.Existing::new)
                .orElseGet(CheckCreation.Active::new);
    }

    @Override
    public Optional<CheckClaim> findClaim(UUID idempotencyKey) {
        return checks.findByIdempotencyKey(idempotencyKey)
                .map(entity -> new CheckClaim(check(entity), entity.requestHash()));
    }

    @Override
    public Optional<FidelityCheck> find(UUID id) {
        return checks.findById(id).map(this::check);
    }

    @Override
    public Optional<FidelityCheck> open() {
        return checks.findFirstByStateInOrderByCreatedAtAsc(OPEN_STATES).map(this::check);
    }

    @Override
    public Page<FidelityCheck> list(FidelityCheckState state, int limit, int offset) {
        var pageable = new OffsetLimit(offset, limit);
        var page = state == null
                ? checks.findAllByOrderByCreatedAtDescCheckIdDesc(pageable)
                : checks.findByStateOrderByCreatedAtDescCheckIdDesc(state.name(), pageable);
        return new Page<>(page.getContent().stream().map(this::check).toList(), page.getTotalElements());
    }

    @Override
    @Transactional
    public boolean update(FidelityCheck next, FidelityCheckState expected) {
        return checks.findWithLockByCheckIdAndState(next.id(), expected.name())
                .map(entity -> {
                    var failure = next.failure();
                    entity.advance(new FidelityCheckJpaEntity.Progress(
                            next.state().name(),
                            next.experimentId(),
                            next.runId(),
                            failure == null ? null : failure.code(),
                            failure == null ? null : failure.message(),
                            next.startedAt(),
                            next.finishedAt()));
                    return true;
                })
                .orElse(false);
    }

    @Override
    public Optional<FidelityCheck> newestPassed(SourceRevisions revisions) {
        return checks.findFirstByStateAndGameRevisionAndTimelineRevisionAndReadRevisionOrderByFinishedAtDesc(
                        FidelityCheckState.PASSED.name(),
                        revisions.gameService(),
                        revisions.timelineService(),
                        revisions.readService())
                .map(this::check);
    }

    @Override
    @Transactional
    public void insertCases(UUID checkId, List<FidelityCase> toInsert) {
        if (cases.countByCheckId(checkId) > 0) {
            return;
        }
        cases.saveAll(toInsert.stream().map(item -> entity(checkId, item)).toList());
    }

    @Override
    public int caseCount(UUID checkId) {
        return Math.toIntExact(cases.countByCheckId(checkId));
    }

    @Override
    public FidelityCounts counts(UUID checkId) {
        return FidelityCounts.of(
                cases.results(checkId).stream().map(FidelityCaseResult::valueOf).toList());
    }

    @Override
    public List<FidelityCase> casesToReproduce(UUID checkId, int limit) {
        return cases
                .findByCheckIdAndResultAndReproductionIdIsNullOrderByOrdinalAsc(
                        checkId, PENDING, Pageable.ofSize(limit))
                .stream()
                .map(this::fidelityCase)
                .toList();
    }

    @Override
    public List<FidelityCase> casesAwaitingReproduction(UUID checkId) {
        return cases.findByCheckIdAndResultAndReproductionIdIsNotNullOrderByOrdinalAsc(checkId, PENDING).stream()
                .map(this::fidelityCase)
                .toList();
    }

    @Override
    @Transactional
    public void updateCase(UUID checkId, FidelityCase updated) {
        var entity = cases.findByCheckIdAndOrdinal(checkId, updated.ordinal()).orElseThrow();
        var failure = updated.failure();
        entity.record(
                updated.reproductionId(),
                updated.result().name(),
                updated.firstDivergence() == null ? null : json.write(updated.firstDivergence()),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message());
    }

    @Override
    public Page<FidelityCase> cases(
            UUID checkId, VariantLabel variant, FidelityCaseResult result, int limit, int offset) {
        var variantName = variant == null ? null : variant.name();
        var resultName = result == null ? null : result.name();
        var ids = caseQueries.rowIds(checkId, variantName, resultName, limit, offset);
        return new Page<>(
                cases.findByRowIdInOrderByOrdinalAsc(ids).stream()
                        .map(this::fidelityCase)
                        .toList(),
                caseQueries.count(checkId, variantName, resultName));
    }

    private static FidelityCaseJpaEntity entity(UUID checkId, FidelityCase item) {
        var rowId = UUID.nameUUIDFromBytes((checkId + "|" + item.ordinal()).getBytes(StandardCharsets.UTF_8));
        return new FidelityCaseJpaEntity(
                rowId,
                checkId,
                item.ordinal(),
                new FidelityCaseJpaEntity.Coordinate(
                        item.caseKey(),
                        item.variant().name(),
                        item.playerCount(),
                        item.factionSet().stream().map(Faction::name).collect(Collectors.joining(",")),
                        item.seatRotation(),
                        item.policyId(),
                        item.runCaseId()));
    }

    private FidelityCheck check(FidelityCheckJpaEntity entity) {
        var failure = entity.failureCode() == null
                ? null
                : new FidelityFailure(entity.failureCode(), entity.failureMessage());
        return new FidelityCheck(
                entity.checkId(),
                entity.seed(),
                FidelityCheckState.valueOf(entity.state()),
                json.read(entity.simulatorJson(), FidelityEnvironment.class),
                json.read(entity.laneJson(), FidelityEnvironment.class),
                entity.laneId(),
                json.read(entity.variantsJson(), new TypeReference<List<FidelityVariant>>() {}),
                entity.experimentId(),
                entity.runId(),
                failure,
                entity.createdAt(),
                entity.startedAt(),
                entity.finishedAt());
    }

    private FidelityCase fidelityCase(FidelityCaseJpaEntity entity) {
        var failure = entity.failureCode() == null
                ? null
                : new FidelityFailure(entity.failureCode(), entity.failureMessage());
        return new FidelityCase(
                entity.ordinal(),
                entity.caseKey(),
                VariantLabel.valueOf(entity.variantLabel()),
                entity.playerCount(),
                Arrays.stream(entity.factionSet().split(","))
                        .map(Faction::valueOf)
                        .toList(),
                entity.seatRotation(),
                entity.policyId(),
                entity.runCaseId(),
                entity.reproductionId(),
                FidelityCaseResult.valueOf(entity.result()),
                entity.divergenceJson() == null ? null : json.read(entity.divergenceJson(), FirstDivergence.class),
                failure);
    }
}
