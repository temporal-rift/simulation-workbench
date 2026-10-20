package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.EffectiveRules;

class ConstraintExpressionTest {

    private static final EffectiveRules RULES = EffectiveRules.parse(new ObjectMapper().readTree("""
                    {"schemaVersion": 1, "properties": {
                      "game.rules.a": 2, "game.rules.b": 3, "game.rules.c-d": 5, "game.rules.e": -4,
                      "game.rules.f": 1.5}}"""));

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "game.rules.a < game.rules.b | true",
                "game.rules.a > game.rules.b | false",
                "game.rules.a <= 2 | true",
                "game.rules.a >= 3 | false",
                "game.rules.a + game.rules.b == game.rules.c-d | true",
                "game.rules.b * game.rules.c-d - game.rules.a == 13 | true",
                "game.rules.a + game.rules.b * game.rules.c-d == 17 | true",
                "(game.rules.a + game.rules.b) * game.rules.c-d == 25 | true",
                "-1 * game.rules.e == 4 | true",
                "game.rules.a < game.rules.b < game.rules.c-d | true",
                "game.rules.a < game.rules.b < game.rules.a | false",
                "max(game.rules.a, game.rules.b) == 3 | true",
                "game.rules.f * 2 == 3 | true",
                "game.rules.a < game.rules.b and game.rules.e < 0 | true",
                "game.rules.a < game.rules.b and game.rules.e > 0 | false"
            })
    void evaluatesOnTheRules(String expression, boolean holds) {
        assertThat(ConstraintExpression.parse(expression).holds(RULES)).isEqualTo(holds);
    }

    @Test
    void reportsTheKeysItReads() {
        var expression = ConstraintExpression.parse("game.rules.b * game.rules.a < max(game.rules.c-d, game.rules.a)");

        assertThat(expression.keys()).containsExactly("game.rules.a", "game.rules.b", "game.rules.c-d");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "game.rules.a | a comparison is missing",
                "game.rules.a < | an operand is missing",
                "game.rules.a < game.rules.b game.rules.a | unexpected",
                "game.rules.a = 2 | unexpected '='",
                "game.rules.a < (game.rules.b | ')' is missing",
                "max(game.rules.a) < 1 | ',' is missing",
                "a < b | unexpected",
                "game.rules.a < 1 and | an operand is missing",
                "game.rules.a < 1 # | unexpected '#'"
            })
    void refusesAMalformedExpression(String expression, String reason) {
        assertThatThrownBy(() -> ConstraintExpression.parse(expression))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void aMissingRuleIsNotSilentlyZero() {
        var expression = ConstraintExpression.parse("game.rules.missing < 1");

        assertThatThrownBy(() -> expression.holds(RULES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("game.rules.missing");
    }
}
