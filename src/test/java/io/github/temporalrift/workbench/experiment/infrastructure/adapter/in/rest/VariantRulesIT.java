package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;

/** A designer states a variant as a baseline plus overrides, previews its rules, freezes it and reads them back. */
@WorkbenchIntegrationTest
class VariantRulesIT {

    private static final String THRESHOLD = "game.rules.win-score-threshold";
    private static final String PUSH_II = "game.rules.probability.push-shift.II";
    private static final String SUPPRESS_II = "game.rules.probability.suppress-shift.II";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JsonNode preview(String overridesJson) throws Exception {
        var request = "{\"rulesArtifact\": {\"digest\": \"" + VariantRulesFixtures.ARTIFACT_DIGEST
                + "\"}, \"parameterSpaceVersion\": 1, \"overrides\": " + overridesJson + "}";
        var response = mockMvc.perform(post("/api/v1/variant-rules-previews")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private String create(String overridesJson) throws Exception {
        var digest = preview(overridesJson).get("effectiveRulesDigest").asString();
        var manifest = VariantRulesFixtures.manifest(
                VariantRulesFixtures.variant(
                        "baseline", VariantRulesFixtures.baseline().digest(), "{}"),
                VariantRulesFixtures.variant("candidate", digest, overridesJson));
        var response = mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write")))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("experimentId").asString();
    }

    private JsonNode rulesOf(String experimentId, String label) throws Exception {
        var response = mockMvc.perform(get("/api/v1/experiments/{id}/variants/{label}/rules", experimentId, label)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void parameterSpaceOneIsServedWithItsParametersConstraintsAndHeldFixedList() throws Exception {
        mockMvc.perform(get("/api/v1/parameter-spaces/1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.parameters.length()").value(46))
                .andExpect(jsonPath("$.parameters[0].key").value(THRESHOLD))
                .andExpect(jsonPath("$.parameters[0].baseline").value(20))
                .andExpect(jsonPath("$.parameters[0].min").value(16))
                .andExpect(jsonPath("$.parameters[0].max").value(26))
                .andExpect(jsonPath("$.parameters[0].step").value(1))
                .andExpect(jsonPath("$.derived.length()").value(4))
                .andExpect(jsonPath("$.constraints.length()").value(14))
                .andExpect(jsonPath("$.constraints[10].id").value("C-11"))
                .andExpect(jsonPath("$.heldFixed[0].key").value("game.rules.max-eras"));
    }

    @Test
    void everyServedParameterSpaceIsListedInVersionOrder() throws Exception {
        mockMvc.perform(get("/api/v1/parameter-spaces")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(1));
    }

    @Test
    void anUnservedParameterSpaceIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/parameter-spaces/99")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void aPreviewReturnsTheRulesTheirDigestAndTheNormalisedOverrides() throws Exception {
        var preview = preview("{\"" + PUSH_II + "\": 25, \"" + THRESHOLD + "\": 20}");

        var rules = EffectiveRules.parse(preview.get("rules"));
        assertThat(rules.number(PUSH_II))
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("25"));
        assertThat(rules.number(SUPPRESS_II))
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("-25"));
        assertThat(preview.get("effectiveRulesDigest").asString()).isEqualTo(rules.digest());
        assertThat(preview.get("overrides").propertyNames()).containsExactly(PUSH_II);
    }

    @Test
    void aPreviewOfTheBaselineValueIsTheBaseline() throws Exception {
        var preview = preview("{\"" + THRESHOLD + "\": 20}");

        assertThat(preview.get("effectiveRulesDigest").asString())
                .isEqualTo(VariantRulesFixtures.baseline().digest());
        assertThat(preview.get("overrides").isEmpty()).isTrue();
    }

    @Test
    void aRefusedPreviewNamesTheReasonAndTheOffendingValue() throws Exception {
        mockMvc.perform(post("/api/v1/variant-rules-previews")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rulesArtifact\": {\"digest\": \"" + VariantRulesFixtures.ARTIFACT_DIGEST
                                + "\"}, \"parameterSpaceVersion\": 1, \"overrides\": {\"" + THRESHOLD + "\": 27}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.reason").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.field").value("overrides." + THRESHOLD));
    }

    @Test
    void aViolatedConstraintIsNamedByItsId() throws Exception {
        mockMvc.perform(post("/api/v1/variant-rules-previews")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rulesArtifact\": {\"digest\": \"" + VariantRulesFixtures.ARTIFACT_DIGEST
                                + "\"}, \"parameterSpaceVersion\": 1,"
                                + " \"overrides\": {\"game.rules.card-category-weights.PARADOX\": 20}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("CONSTRAINT_VIOLATED"))
                .andExpect(jsonPath("$.constraintId").value("C-11"));
    }

    @Test
    void aFrozenVariantServesTheRulesItsDigestNames() throws Exception {
        var experimentId = create("{\"" + PUSH_II + "\": 25, \"" + THRESHOLD + "\": 22}");

        for (var label : new String[] {"baseline", "candidate"}) {
            var rules = EffectiveRules.parse(rulesOf(experimentId, label));
            var frozen = objectMapper.readTree(jdbcTemplate.queryForObject(
                    "select manifest_json from experiment where experiment_id = ?",
                    String.class,
                    UUID.fromString(experimentId)));
            var variant = frozen.get("variants")
                    .valueStream()
                    .filter(candidate -> label.equals(candidate.get("label").asString()))
                    .findFirst()
                    .orElseThrow();
            assertThat(rules.digest())
                    .as(label)
                    .isEqualTo(variant.get("effectiveRulesDigest").asString());
        }
        assertThat(EffectiveRules.parse(rulesOf(experimentId, "candidate")).number(THRESHOLD))
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("22"));
    }

    @Test
    void variantsListingTheSameOverridesInAnotherOrderFreezeTheSameRules() throws Exception {
        var forward = create("{\"" + PUSH_II + "\": 25, \"" + THRESHOLD + "\": 22}");
        var reverse = create("{\"" + THRESHOLD + "\": 22, \"" + PUSH_II + "\": 25}");

        assertThat(rulesOf(reverse, "candidate")).isEqualTo(rulesOf(forward, "candidate"));
        assertThat(frozenVariant(reverse, "candidate")).isEqualTo(frozenVariant(forward, "candidate"));
    }

    private JsonNode frozenVariant(String experimentId, String label) throws Exception {
        var response = mockMvc.perform(get("/api/v1/experiments/{id}", experimentId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper
                .readTree(response)
                .get("manifest")
                .get("variants")
                .valueStream()
                .filter(candidate -> label.equals(candidate.get("label").asString()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theFrozenBaselineDocumentIsRetainedWithTheExperiment() throws Exception {
        var experimentId = create("{\"" + PUSH_II + "\": 25}");

        var retained = jdbcTemplate.queryForObject(
                "select document from experiment_baseline where experiment_id = ? and rules_artifact_digest = ?",
                String.class,
                UUID.fromString(experimentId),
                VariantRulesFixtures.ARTIFACT_DIGEST);
        assertThat(retained).isEqualTo(VariantRulesFixtures.baselineText());
    }

    @Test
    void aFrozenVariantWithoutRetainedRulesOrAnUnknownLabelIsNotFound() throws Exception {
        var experimentId = create("{\"" + PUSH_II + "\": 25}");

        mockMvc.perform(get("/api/v1/experiments/{id}/variants/{label}/rules", experimentId, "missing")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/experiments/{id}/variants/{label}/rules", UUID.randomUUID(), "baseline")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void creatingWithARefusedOverrideNamesTheValueAtItsPlaceInTheManifest() throws Exception {
        var manifest = VariantRulesFixtures.manifest(
                VariantRulesFixtures.variant(
                        "baseline", VariantRulesFixtures.baseline().digest(), "{}"),
                VariantRulesFixtures.variant("candidate", "f".repeat(64), "{\"" + THRESHOLD + "\": 27}"));

        mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write")))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPERIMENT"))
                .andExpect(jsonPath("$.reason").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.field").value("variants[1].overrides." + THRESHOLD));
    }
}
