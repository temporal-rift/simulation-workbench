package io.github.temporalrift.workbench.proof;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;

class EvidenceWriterTest {

    private static final String DIGEST = "d".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void multiSeedEvidenceCarriesEveryAttributionAndNoCredential() throws Exception {
        var writer = new EvidenceWriter(mapper, directory);

        var evidence = writer.multiSeedSample(experiment(), run(), report(), List.of(savedCase()));
        var file = writer.write("multi-seed-sample.json", evidence);

        assertThat(evidence.path("formatVersion").asInt()).isEqualTo(EvidenceWriter.FORMAT_VERSION);
        assertThat(evidence.path("kind").asString()).isEqualTo("MULTI_SEED_SAMPLE");
        assertThat(evidence.path("manifestDigest").asString()).isEqualTo(DIGEST);
        assertThat(evidence.path("analysisVersion").asString()).isEqualTo("1");
        assertThat(evidence.path("timingMode").asString()).isEqualTo("LOGICAL");
        assertThat(evidence.path("services")
                        .path("gameService")
                        .path("imageDigest")
                        .asString())
                .startsWith("sha256:");
        assertThat(evidence.path("contracts").path("session-api").asString()).isEqualTo("3.0.0");
        assertThat(evidence.path("policies")).hasSize(2);
        assertThat(evidence.path("policies").get(0).path("artifactDigest").asString())
                .isNotBlank();
        assertThat(evidence.path("variants")).hasSize(2);
        assertThat(evidence.path("cases").get(0).path("semanticDigest").asString())
                .isEqualTo(DIGEST);
        assertThat(java.nio.file.Files.readString(file))
                .doesNotContainIgnoringCase("bearer")
                .doesNotContainIgnoringCase("token");
    }

    @Test
    void reproductionEvidenceNamesTheSavedCaseAndHowTheReExecutionEnded() {
        var evidence =
                new EvidenceWriter(mapper, directory).reproduction(experiment(), savedCase(), mapper.readTree("""
                        {"reproductionId":"00000000-0000-0000-0000-000000000009","state":"MATCH","firstDivergence":null}
                        """));

        assertThat(evidence.path("kind").asString()).isEqualTo("EXACT_REPRODUCTION");
        assertThat(evidence.path("savedSemanticDigest").asString()).isEqualTo(DIGEST);
        assertThat(evidence.path("state").asString()).isEqualTo("MATCH");
        assertThat(evidence.path("firstDivergence").isNull()).isTrue();
        assertThat(evidence.path("manifestDigest").asString()).isEqualTo(DIGEST);
    }

    @Test
    void theSameInputsProduceTheSameDocument() {
        var writer = new EvidenceWriter(mapper, directory);

        var first = writer.multiSeedSample(experiment(), run(), report(), List.of(savedCase()));
        var second = writer.multiSeedSample(experiment(), run(), report(), List.of(savedCase()));

        assertThat(first).isEqualTo(second);
    }

    private JsonNode experiment() {
        var experiment = mapper.createObjectNode();
        experiment.put("experimentId", "00000000-0000-0000-0000-000000000001");
        experiment.put("manifestDigest", DIGEST);
        experiment.set("manifest", ExperimentManifests.valid());
        return experiment;
    }

    private JsonNode run() {
        return mapper.readTree("{\"runId\":\"00000000-0000-0000-0000-000000000002\"}");
    }

    private JsonNode report() {
        return mapper.readTree("""
                {"analysisVersion":"1","analysisSeed":"1","complete":true,
                 "caseCounts":{"requested":1,"succeeded":1},"cohorts":[]}
                """);
    }

    private JsonNode savedCase() {
        return mapper.readTree("""
                {"runId":"00000000-0000-0000-0000-000000000002","caseId":"00000000-0000-0000-0000-000000000003",
                 "caseKey":"00000000-0000-0000-0000-000000000004","variantLabel":"baseline","seed":"42",
                 "state":"SUCCEEDED","result":{"semanticDigest":"%s"}}
                """.formatted(DIGEST));
    }
}
