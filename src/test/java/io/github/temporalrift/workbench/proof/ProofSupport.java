package io.github.temporalrift.workbench.proof;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;

/** Shared harness of the application proof: the HTTP client, a worker that drains a run, and a clean store. */
@WorkbenchIntegrationTest
abstract class ProofSupport {

    static final String BASELINE = "baseline";
    static final String CANDIDATE = "candidate";
    static final String ERASERS = "ERASERS";

    private static final String WORKER = "proof-worker";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected RunBatchUseCase batch;

    @Autowired
    protected ScriptedLanes lanes;

    @Autowired
    protected JdbcTemplate jdbc;

    protected ProofApi api;

    /** The experiment most recently frozen by {@link #startRun}. */
    protected UUID experimentId;

    @BeforeEach
    @AfterEach
    void cleanStore() {
        jdbc.update("DELETE FROM reproduction");
        jdbc.update("DELETE FROM analysis_comparison");
        jdbc.update("DELETE FROM analysis_case_fact");
        jdbc.update("DELETE FROM evidence_event");
        jdbc.update("DELETE FROM evidence_step");
        jdbc.update("DELETE FROM evidence_source_offset");
        jdbc.update("DELETE FROM case_evidence");
        jdbc.update("DELETE FROM evidence_artifact");
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        lanes.reset();
        api = new ProofApi(mockMvc, objectMapper);
    }

    /** Freezes the manifest and starts its run, both over HTTP. */
    protected UUID startRun(JsonNode manifest) throws Exception {
        experimentId = api.freeze(manifest);
        return api.start(experimentId);
    }

    /** Claims and executes cases until nothing is pending, as the worker does. */
    protected void drain() {
        batch.maintain();
        while (batch.runNextCase(WORKER)) {
            // keep claiming until nothing is pending
        }
        batch.maintain();
    }

    /** The full coverage matrix: every faction set of 3, 4 and 5 players, both policies, two rules variants. */
    protected JsonNode coverageManifest() {
        var manifest = (ObjectNode)
                ExperimentManifests.valid("42", "a".repeat(64), "b".repeat(64)).deepCopy();
        ((ObjectNode) manifest.withArray("variants").get(1)).put("effectiveRulesDigest", "f".repeat(64));
        return manifest;
    }

    /** One three-player faction set over the given seeds and the two rules variants: six cases per seed. */
    protected JsonNode threePlayerManifest(List<String> seeds, boolean twoVariants) {
        var manifest = (ObjectNode) (twoVariants
                        ? ExperimentManifests.threePlayerTwoVariants()
                        : ExperimentManifests.threePlayerSingleSet())
                .deepCopy();
        var values = objectMapper.createArrayNode();
        seeds.forEach(values::add);
        manifest.set("seeds", values);
        return manifest;
    }
}
