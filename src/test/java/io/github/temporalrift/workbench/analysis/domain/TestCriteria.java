package io.github.temporalrift.workbench.analysis.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.configuration.ConfiguredBalanceCriteria;

/** The configured criterion versions, as served, and variants of them for tests that need another sample size. */
public final class TestCriteria {

    private static final String RESOURCE = "balance-criteria/v1.json";
    private static final String VERSION_2 = "balance-criteria/v2.json";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private TestCriteria() {}

    public static BalanceCriterion v1() {
        return ConfiguredBalanceCriteria.parse(MAPPER, source());
    }

    /** Version 1 with a smaller confirmation sample, so a full matrix stays small. */
    public static BalanceCriterion v1WithMinimumSeeds(int seeds) {
        return ConfiguredBalanceCriteria.parse(
                MAPPER,
                source().replace("\"confirmationMinimumSeeds\": 100", "\"confirmationMinimumSeeds\": " + seeds));
    }

    public static BalanceCriterion v2() {
        return ConfiguredBalanceCriteria.parse(MAPPER, sourceOf(VERSION_2));
    }

    /** Version 2 with a smaller confirmation sample, so a full matrix stays small. */
    public static BalanceCriterion v2WithMinimumSeeds(int seeds) {
        return ConfiguredBalanceCriteria.parse(
                MAPPER,
                sourceOf(VERSION_2)
                        .replace("\"confirmationMinimumSeeds\": 104", "\"confirmationMinimumSeeds\": " + seeds));
    }

    public static String source() {
        return sourceOf(RESOURCE);
    }

    public static String v2Source() {
        return sourceOf(VERSION_2);
    }

    private static String sourceOf(String resource) {
        try {
            return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
