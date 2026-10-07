package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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

        assertThat(stored).contains("effectiveRulesDigest");
        assertThat(stored).contains("gameService");
        assertThat(stored).contains("seatRotationMode");
        assertThat(stored.toLowerCase()).doesNotContain("password", "credentials", "/home/", "file://", "c:\\", "c:/");
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

    private Long count() {
        return jdbcTemplate.queryForObject("select count(*) from experiment", Long.class);
    }

    private String manifestJsonByDigest(String digest) {
        return jdbcTemplate.queryForObject(
                "select manifest_json from experiment where manifest_digest = ?", String.class, digest);
    }

    private static SimpleGrantedAuthority writeAuthority() {
        return new SimpleGrantedAuthority("SCOPE_simulation:write");
    }
}
