package io.github.temporalrift.workbench.proof;

import static io.github.temporalrift.workbench.ExecutionTestConfiguration.DEFAULT_FIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;

/** A run that stopped part-way, as the designer sees it over HTTP: nothing already earned is lost or counted twice. */
class RecoveryProofIT extends ProofSupport {

    private static final String WORKER = "proof-recovery-worker";
    private static final int REQUESTED = 1000;
    private static final int KEPT = 400;

    @Autowired
    private CaseLedger caseLedger;

    @Autowired
    private Clock clock;

    @Test
    void aThousandCaseRunInterruptedAfterFourHundredSuccessesKeepsThemAndNeverExceedsAThousand() throws Exception {
        var runId = startRun(ExperimentManifests.fourPlayerSeeds(50, 4));
        assertThat(api.run(runId).path("counts").path("requested").asInt()).isEqualTo(REQUESTED);
        batch.maintain();
        for (var done = 0; done < KEPT; done++) {
            assertThat(batch.runNextCase(WORKER)).isTrue();
        }
        var kept = succeeded(runId);
        assertThat(kept).hasSize(KEPT);
        // A worker that crashed mid-case leaves an attempt running and holding a lease.
        var now = clock.instant();
        caseLedger
                .claimNext("crashed-worker", now, now.plusSeconds(30), Set.of(DEFAULT_FIT))
                .orElseThrow();

        batch.recoverAfterRestart();

        var interrupted = api.run(runId);
        assertThat(interrupted.path("state").asString()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.path("counts").path("succeeded").asInt()).isEqualTo(KEPT);
        assertThat(interrupted.path("counts").path("pending").asInt()).isEqualTo(REQUESTED - KEPT);
        assertThat(interrupted.path("counts").path("running").asInt()).isZero();

        api.resume(runId).andExpect(status().isAccepted());
        api.resume(runId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").exists());
        drain();

        var finished = api.run(runId);
        assertThat(finished.path("state").asString()).isEqualTo("COMPLETED");
        assertThat(finished.path("counts").path("succeeded").asInt()).isEqualTo(REQUESTED);
        assertThat(finished.path("counts").path("requested").asInt()).isEqualTo(REQUESTED);
        var all = succeeded(runId);
        assertThat(all).hasSize(REQUESTED);
        kept.forEach((caseId, snapshot) -> assertThat(all).containsEntry(caseId, snapshot));
    }

    /** Each succeeded case as the designer reads it, by case identifier. */
    private Map<String, JsonNode> succeeded(java.util.UUID runId) throws Exception {
        var cases = new LinkedHashMap<String, JsonNode>();
        for (var summary : api.caseSummaries(runId)) {
            if ("SUCCEEDED".equals(summary.path("state").asString())) {
                var caseId = summary.path("caseId").asString();
                cases.put(caseId, api.caseOf(runId, caseId));
            }
        }
        return cases;
    }
}
