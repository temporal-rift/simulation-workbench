package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;

@WorkbenchIntegrationTest
class CreateExperimentIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void completeManifestIsFrozenWithDigest() throws Exception {
        var body = objectMapper.writeValueAsString(ExperimentManifests.valid("100", "a".repeat(64), "b".repeat(64)));

        mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.experimentId").isString())
                .andExpect(jsonPath("$.manifestDigest").value(org.hamcrest.Matchers.matchesPattern("[a-f0-9]{64}")))
                .andExpect(jsonPath("$.manifest.name").value("threshold experiment"))
                .andExpect(jsonPath("$.createdAt").isString());
    }

    @Test
    void duplicateFactionIsRejectedWithoutStorage() throws Exception {
        var before = count();
        var manifest = (tools.jackson.databind.node.ObjectNode)
                ExperimentManifests.valid().deepCopy();
        var sets = manifest.withArray("factionSets");
        var dupe = sets.arrayNode();
        dupe.add("WEAVERS");
        dupe.add("WEAVERS");
        dupe.add("WEAVERS");
        sets.add(dupe);

        mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPERIMENT"));

        assertThat(count()).isEqualTo(before);
    }

    @Test
    void rulesChangeCreatesNewExperimentAndLeavesOriginalByteIdentical() throws Exception {
        var threshold20 = ExperimentManifests.valid("101", "a".repeat(64), "b".repeat(64));
        var threshold22 = ExperimentManifests.valid("101", "c".repeat(63) + "0", "b".repeat(64));

        String digest20 = create(threshold20);
        String storedBefore = manifestJsonByDigest(digest20);
        String digest22 = create(threshold22);

        assertThat(digest22).isNotEqualTo(digest20);
        assertThat(manifestJsonByDigest(digest20)).isEqualTo(storedBefore);
    }

    @Test
    void retainedArtifactsCarryNoSecretsOrLocalPaths() throws Exception {
        String digest = create(ExperimentManifests.valid("102", "a".repeat(64), "b".repeat(64)));

        String stored = manifestJsonByDigest(digest);

        assertThat(stored)
                .contains("effectiveRulesDigest")
                .contains("gameService")
                .contains("seatRotationMode");
        assertThat(stored.toLowerCase()).doesNotContain("password", "credentials", "/home/", "file://", "c:\\", "c:/");
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        var body = objectMapper.writeValueAsString(ExperimentManifests.valid("105", "a".repeat(64), "b".repeat(64)));

        mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(writeAuthority()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parallelRequestsWithSameKeyReturnSingleExperiment() throws Exception {
        var key = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(ExperimentManifests.singleSeedSinglePolicySingleVariant("106"));
        var gate = new CountDownLatch(1);
        Callable<String> request = () -> {
            gate.await(10, TimeUnit.SECONDS);
            MvcResult result = mockMvc.perform(post("/api/v1/experiments")
                            .with(jwt().authorities(writeAuthority()))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();
            return objectMapper
                    .readTree(result.getResponse().getContentAsString())
                    .get("experimentId")
                    .asString();
        };

        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = List.of(pool.submit(request), pool.submit(request));
            gate.countDown();
            var ids = List.of(
                    futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));

            assertThat(ids.get(0)).isEqualTo(ids.get(1));
            assertThat(countByDigest(digestOf(body))).isEqualTo(1);
        }
    }

    @Test
    void repeatedKeyWithIdenticalBodyReturnsOriginalAndChangedBodyConflicts() throws Exception {
        var key = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(ExperimentManifests.singleSeedSinglePolicySingleVariant("103"));

        MvcResult first = mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper
                .readTree(first.getResponse().getContentAsString())
                .get("experimentId")
                .asString();

        MvcResult replay = mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String replayId = objectMapper
                .readTree(replay.getResponse().getContentAsString())
                .get("experimentId")
                .asString();
        assertThat(replayId).isEqualTo(firstId);

        var changed = objectMapper.writeValueAsString(ExperimentManifests.valid("104", "a".repeat(64), "b".repeat(64)));
        mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private String create(JsonNode manifest) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("manifestDigest")
                .asString();
    }

    private String digestOf(String body) throws Exception {
        return ManifestDigest.sha256Hex(objectMapper.readTree(body));
    }

    private Long count() {
        return jdbcTemplate.queryForObject("select count(*) from experiment", Long.class);
    }

    private Long countByDigest(String digest) {
        return jdbcTemplate.queryForObject(
                "select count(*) from experiment where manifest_digest = ?", Long.class, digest);
    }

    private String manifestJsonByDigest(String digest) {
        return jdbcTemplate.queryForObject(
                "select manifest_json from experiment where manifest_digest = ?", String.class, digest);
    }

    private static SimpleGrantedAuthority writeAuthority() {
        return new SimpleGrantedAuthority("SCOPE_simulation:write");
    }
}
