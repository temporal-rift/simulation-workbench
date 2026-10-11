package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunState;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;

/** Maps the run, case and attempt entities to the domain values the ports speak. */
@Component
class Mappings {

    private static final TypeReference<List<SeatPlan>> SEATS = new TypeReference<>() {};

    private final StoredJson json;

    Mappings(StoredJson json) {
        this.json = json;
    }

    Run run(RunJpaEntity entity) {
        return Run.restore(
                entity.runId(),
                entity.experimentId(),
                RunState.valueOf(entity.state()),
                entity.createdAt(),
                entity.startedAt(),
                entity.finishedAt(),
                failure(entity.failureCode(), entity.failureMessage()));
    }

    LogicalCase logicalCase(RunCaseJpaEntity entity) {
        return new LogicalCase(
                entity.caseId(),
                entity.runId(),
                entity.caseKey(),
                entity.ordinal(),
                entity.variantLabel(),
                entity.seed(),
                entity.playerCount(),
                json.read(entity.seatsJson(), SEATS),
                CaseState.valueOf(entity.state()),
                entity.resultJson() == null ? null : json.read(entity.resultJson(), CaseResult.class));
    }

    Attempt attempt(CaseAttemptJpaEntity entity) {
        return new Attempt(
                entity.attemptId(),
                entity.caseId(),
                entity.ordinal(),
                AttemptState.valueOf(entity.state()),
                entity.gameId(),
                entity.laneId(),
                entity.startedAt(),
                entity.finishedAt(),
                failure(entity.failureCode(), entity.failureMessage()));
    }

    static Failure failure(String code, String message) {
        return code == null ? null : new Failure(FailureCode.valueOf(code), message);
    }

    static String failureCode(Failure failure) {
        return failure == null ? null : failure.code().name();
    }

    static String failureMessage(Failure failure) {
        return failure == null ? null : failure.message();
    }
}
