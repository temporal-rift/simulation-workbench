package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository.NewCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;

/**
 * Stores a run, its idempotency claim and all its cases in one transaction, so a lost claim leaves nothing behind.
 * The cases go in batches whose persistence context is released, since a run may have up to 100,000 of them.
 */
@Component
class RunInserter {

    private static final int BATCH = 500;
    private static final String START_RUN = "startRun";

    private final RunJpaRepository runs;
    private final RunCommandJpaRepository commands;
    private final RunCaseJpaRepository cases;
    private final StoredJson json;

    @PersistenceContext
    private EntityManager entityManager;

    RunInserter(RunJpaRepository runs, RunCommandJpaRepository commands, RunCaseJpaRepository cases, StoredJson json) {
        this.runs = runs;
        this.commands = commands;
        this.cases = cases;
        this.json = json;
    }

    @Transactional
    void insert(UUID idempotencyKey, String requestHash, Run run, int concurrency, List<NewCase> newCases) {
        runs.save(new RunJpaEntity(run.runId(), run.experimentId(), run.state().name(), concurrency, run.createdAt()));
        commands.saveAndFlush(
                new RunCommandJpaEntity(idempotencyKey, START_RUN, run.runId(), requestHash, run.createdAt()));
        for (var from = 0; from < newCases.size(); from += BATCH) {
            var batch = newCases.subList(from, Math.min(from + BATCH, newCases.size())).stream()
                    .map(newCase -> entity(run.runId(), newCase))
                    .toList();
            cases.saveAll(batch);
            cases.flush();
            entityManager.clear();
        }
    }

    private RunCaseJpaEntity entity(UUID runId, NewCase newCase) {
        return new RunCaseJpaEntity(UUID.randomUUID(), runId, newCase, json.write(newCase.seats()));
    }
}
