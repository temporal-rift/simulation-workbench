package io.github.temporalrift.workbench.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

class EffectiveRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EffectiveRules rules(String json) {
        return EffectiveRules.parse(MAPPER.readTree(json));
    }

    @Test
    void thePublishedExampleHasThePublishedDigest() {
        var rules = rules("""
                {"schemaVersion": 1, "properties": {
                  "game.rules.win-score-threshold": 20,
                  "game.rules.probability.amplify-multiplier.II": 2}}""");

        assertThat(rules.digest()).isEqualTo("2f4e77d6f2f5ad41eb3b05de091e524133995ef6f274a58f539adbf2d5b06fad");
    }

    @Test
    void keyOrderAndNumberSpellingDoNotChangeTheDigest() {
        var published = rules("""
                {"schemaVersion": 1, "properties": {
                  "game.rules.win-score-threshold": 20,
                  "game.rules.probability.amplify-multiplier.II": 2}}""");
        var reordered = rules("""
                {"properties": {
                  "game.rules.probability.amplify-multiplier.II": 2.0,
                  "game.rules.win-score-threshold": 2.0E1},
                 "schemaVersion": 1}""");

        assertThat(reordered.digest()).isEqualTo(published.digest());
    }

    @Test
    void aDifferentValueChangesTheDigest() {
        var one = rules("{\"schemaVersion\": 1, \"properties\": {\"game.rules.a\": 1.5}}");
        var other = rules("{\"schemaVersion\": 1, \"properties\": {\"game.rules.a\": 1.25}}");

        assertThat(other.digest()).isNotEqualTo(one.digest());
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0",
        "-0.0, 0",
        "20, 20",
        "2.0, 2",
        "1.5, 1.5",
        "-25, -25",
        "0.25, 0.25",
        "0.000001, 0.000001",
        "0.0000001, 1e-7",
        "1000000, 1000000",
        "1e21, 1e+21",
        "123456789012345680000, 123456789012345680000",
        "1.2345e25, 1.2345e+25",
        "0.1, 0.1",
        "100, 100"
    })
    void numbersAreWrittenAsEcmaScriptWritesThem(double value, String expected) {
        assertThat(RulesCanonicalJson.number(value)).isEqualTo(expected);
    }

    @Test
    void stringsBooleansAndArraysKeepTheirServedOrder() {
        var rules = rules("""
                {"schemaVersion": 1, "properties": {
                  "game.rules.specials": ["SEAL", "CORRUPT"],
                  "game.rules.flag": true,
                  "game.rules.label": "a\\"b\\n"}}""");

        assertThat(RulesCanonicalJson.write(rules.properties()))
                .isEqualTo("{\"game.rules.flag\":true,\"game.rules.label\":\"a\\\"b\\n\","
                        + "\"game.rules.specials\":[\"SEAL\",\"CORRUPT\"]}");
    }

    @Test
    void aDocumentMustHaveTheSchemaVersionAndRulesKeys() {
        assertThatThrownBy(() -> rules("{\"schemaVersion\": 2, \"properties\": {}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> rules("{\"schemaVersion\": 1, \"properties\": {\"other.key\": 1}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other.key");
        assertThatThrownBy(() -> rules("{\"schemaVersion\": 1, \"properties\": {\"game.rules.a\": {\"b\": 1}}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("game.rules.a");
    }
}
