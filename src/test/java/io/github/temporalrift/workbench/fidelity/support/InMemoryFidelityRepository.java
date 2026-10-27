package io.github.temporalrift.workbench.fidelity.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.fidelity.domain.CheckClaim;
import io.github.temporalrift.workbench.fidelity.domain.CheckCreation;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCaseResult;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCounts;
import io.github.temporalrift.workbench.fidelity.domain.SourceRevisions;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;
import io.github.temporalrift.workbench.shared.Page;

/** Fidelity records in memory, behaving as the database adapter does. */
public class InMemoryFidelityRepository implements FidelityRepository {

    private record Claim(UUID checkId, String requestHash) {}

    private final Map<UUID, FidelityCheck> checks = new LinkedHashMap<>();
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final Map<UUID, List<FidelityCase>> cases = new LinkedHashMap<>();

    @Override
    public synchronized CheckCreation create(UUID idempotencyKey, String requestHash, FidelityCheck queued) {
        var claim = findClaim(idempotencyKey);
        if (claim.isPresent()) {
            return new CheckCreation.Existing(claim.get());
        }
        if (open().isPresent()) {
            return new CheckCreation.Active();
        }
        checks.put(queued.id(), queued);
        claims.put(idempotencyKey, new Claim(queued.id(), requestHash));
        return new CheckCreation.Created();
    }

    @Override
    public synchronized Optional<CheckClaim> findClaim(UUID idempotencyKey) {
        return Optional.ofNullable(claims.get(idempotencyKey))
                .map(claim -> new CheckClaim(checks.get(claim.checkId()), claim.requestHash()));
    }

    @Override
    public synchronized Optional<FidelityCheck> find(UUID id) {
        return Optional.ofNullable(checks.get(id));
    }

    @Override
    public synchronized Optional<FidelityCheck> open() {
        return checks.values().stream().filter(check -> check.state().isOpen()).findFirst();
    }

    @Override
    public synchronized Page<FidelityCheck> list(FidelityCheckState state, int limit, int offset) {
        var all = checks.values().stream()
                .filter(check -> state == null || check.state() == state)
                .sorted(Comparator.comparing(FidelityCheck::createdAt).reversed())
                .toList();
        return Page.of(all, limit, offset);
    }

    @Override
    public synchronized boolean update(FidelityCheck next, FidelityCheckState expected) {
        var current = checks.get(next.id());
        if (current == null || current.state() != expected) {
            return false;
        }
        checks.put(next.id(), next);
        return true;
    }

    @Override
    public synchronized Optional<FidelityCheck> newestPassed(SourceRevisions revisions) {
        return checks.values().stream()
                .filter(check -> check.state() == FidelityCheckState.PASSED)
                .filter(check -> check.simulator().sourceRevisions().equals(revisions))
                .max(Comparator.comparing(FidelityCheck::finishedAt));
    }

    @Override
    public synchronized void insertCases(UUID checkId, List<FidelityCase> toInsert) {
        cases.putIfAbsent(checkId, new ArrayList<>(toInsert));
    }

    @Override
    public synchronized int caseCount(UUID checkId) {
        return cases.getOrDefault(checkId, List.of()).size();
    }

    @Override
    public synchronized FidelityCounts counts(UUID checkId) {
        return FidelityCounts.of(cases.getOrDefault(checkId, List.of()).stream()
                .map(FidelityCase::result)
                .toList());
    }

    @Override
    public synchronized List<FidelityCase> casesToReproduce(UUID checkId, int limit) {
        return cases.getOrDefault(checkId, List.of()).stream()
                .filter(item -> item.result() == FidelityCaseResult.PENDING && item.reproductionId() == null)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<FidelityCase> casesAwaitingReproduction(UUID checkId) {
        return cases.getOrDefault(checkId, List.of()).stream()
                .filter(item -> item.result() == FidelityCaseResult.PENDING && item.reproductionId() != null)
                .toList();
    }

    @Override
    public synchronized void updateCase(UUID checkId, FidelityCase updated) {
        cases.get(checkId).set(updated.ordinal(), updated);
    }

    @Override
    public synchronized Page<FidelityCase> cases(
            UUID checkId, VariantLabel variant, FidelityCaseResult result, int limit, int offset) {
        var matching = cases.getOrDefault(checkId, List.of()).stream()
                .filter(item -> variant == null || item.variant() == variant)
                .filter(item -> result == null || item.result() == result)
                .toList();
        return Page.of(matching, limit, offset);
    }
}
