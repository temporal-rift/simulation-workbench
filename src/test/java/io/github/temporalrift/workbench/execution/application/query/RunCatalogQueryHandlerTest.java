package io.github.temporalrift.workbench.execution.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.RunCase;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;

class RunCatalogQueryHandlerTest {

    private static final UUID RUN = new UUID(0, 1);
    private static final UUID FAILED = new UUID(1, 1);
    private static final UUID RETRIED = new UUID(1, 2);
    private final RunRepository runs = mock(RunRepository.class);
    private final RunCatalogQueryHandler catalog = new RunCatalogQueryHandler(runs, mock(EvidenceLedger.class));

    @Test
    void aFailedCaseCarriesTheCodeOfItsFinalFailedAttemptAndOtherCasesCarryNone() {
        when(runs.cases(RUN))
                .thenReturn(List.of(
                        logicalCase(FAILED, CaseState.FAILED),
                        logicalCase(RETRIED, CaseState.RUNNING),
                        logicalCase(new UUID(1, 3), CaseState.PENDING)));
        when(runs.finalFailureCodes(RUN)).thenReturn(Map.of(FAILED, FailureCode.POLICY_EXHAUSTED));

        var cases = catalog.cases(RUN);

        assertThat(cases).extracting(RunCase::failureCode).containsExactly("POLICY_EXHAUSTED", null, null);
    }

    private static LogicalCase logicalCase(UUID caseId, CaseState state) {
        var digest = "a".repeat(64);
        return new LogicalCase(
                caseId,
                RUN,
                UUID.randomUUID(),
                0,
                "base",
                new BundleDigests(digest, digest),
                "1",
                3,
                List.of(
                        new SeatPlan(0, "ERASERS", "random-v1", "1"),
                        new SeatPlan(1, "PROPHETS", "random-v1", "1"),
                        new SeatPlan(2, "WEAVERS", "random-v1", "1")),
                state,
                null);
    }
}
