package io.github.temporalrift.workbench.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.execution.application.port.in.RunReproductionUseCase;

/** Reproducing a saved case through the published boundary: an exact match, or a refusal that creates nothing. */
class ReproductionProofIT extends ProofSupport {

    private static final String WORKER = "proof-reproducer";

    @Autowired
    private RunReproductionUseCase reproductions;

    @Test
    void aSavedCaseReproducesWithAnEqualDigestAndAddsNoSample() throws Exception {
        var runId = startRun(threePlayerManifest(List.of("42"), false));
        drain();
        var saved = firstCase(runId);
        var attemptsBefore = jdbc.queryForObject("SELECT count(*) FROM case_attempt", Integer.class);
        var countsBefore = api.run(runId).path("counts");
        var key = UUID.randomUUID();

        var queued = api.json(api.reproduce(runId, saved.path("caseId").asString(), ProofApi.writer(), key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("QUEUED")));
        assertThat(reproductions.runNext(WORKER)).isTrue();
        var settled = api.json(api.reproduce(runId, saved.path("caseId").asString(), ProofApi.writer(), key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reproductionId")
                        .value(queued.path("reproductionId").asString())));

        assertThat(settled.path("state").asString()).isEqualTo("MATCH");
        assertThat(settled.path("firstDivergence").isNull()).isTrue();
        var after = api.caseOf(runId, saved.path("caseId").asString());
        assertThat(after.path("result").path("semanticDigest"))
                .isEqualTo(saved.path("result").path("semanticDigest"));
        assertThat(after.path("attempts")).hasSize(1);
        assertThat(api.run(runId).path("counts")).isEqualTo(countsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_attempt", Integer.class))
                .isEqualTo(attemptsBefore);

        var writer = new EvidenceWriter(objectMapper, EvidenceWriter.DEFAULT_DIRECTORY);
        var document = writer.reproduction(api.experiment(experimentId), saved, settled);
        var file = writer.write("reproduction.json", document);
        assertThat(document.path("state").asString()).isEqualTo("MATCH");
        assertThat(document.path("savedSemanticDigest").asString())
                .isEqualTo(saved.path("result").path("semanticDigest").asString());
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("EXACT_REPRODUCTION");
    }

    @Test
    void aTamperedPinnedArtifactIsRefusedAndCreatesNoReproduction() throws Exception {
        var runId = startRun(threePlayerManifest(List.of("42"), false));
        drain();
        var saved = firstCase(runId);
        jdbc.update(
                "UPDATE evidence_artifact SET content = ? WHERE digest = (SELECT transcript_artifact FROM"
                        + " case_evidence WHERE scope_id = ?)",
                "tampered".getBytes(StandardCharsets.UTF_8),
                UUID.fromString(saved.path("caseId").asString()));

        api.reproduce(runId, saved.path("caseId").asString(), ProofApi.writer(), UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_MISMATCH"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reproduction", Integer.class))
                .isZero();
    }

    @Test
    void aManifestThatIsNoLongerTheFrozenOneIsRefusedAndCreatesNoReproduction() throws Exception {
        var runId = startRun(threePlayerManifest(List.of("42"), false));
        drain();
        var saved = firstCase(runId);
        jdbc.update(
                "UPDATE case_evidence SET manifest_digest = ? WHERE scope_id = ?",
                "f".repeat(64),
                UUID.fromString(saved.path("caseId").asString()));

        api.reproduce(runId, saved.path("caseId").asString(), ProofApi.writer(), UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_MISMATCH"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reproduction", Integer.class))
                .isZero();
    }

    @Test
    void aCaseWithNothingSavedHasNoPinnedInputsToReproduceFrom() throws Exception {
        var runId = startRun(threePlayerManifest(List.of("42"), false));
        var unfinished = api.caseSummaries(runId).getFirst();

        api.reproduce(runId, unfinished.path("caseId").asString(), ProofApi.writer(), UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_MISMATCH"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reproduction", Integer.class))
                .isZero();
    }

    private JsonNode firstCase(UUID runId) throws Exception {
        return api.caseOf(
                runId, api.caseSummaries(runId).getFirst().path("caseId").asString());
    }
}
