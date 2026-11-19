package io.github.temporalrift.workbench.analysis.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.configuration.ConfiguredBalanceCriteria;

/** The configured criterion, as served, and a variant of it for tests that need another sample size. */
public final class TestCriteria {

    private static final String RESOURCE = "balance-criteria/balance-criterion.json";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private TestCriteria() {}

    public static BalanceCriterion current() {
        return ConfiguredBalanceCriteria.parse(MAPPER, source());
    }

    /** The criterion with a smaller confirmation sample, so a full matrix stays small. */
    public static BalanceCriterion currentWithMinimumSeeds(int seeds) {
        return ConfiguredBalanceCriteria.parse(
                MAPPER,
                source().replace("\"confirmationMinimumSeeds\": 104", "\"confirmationMinimumSeeds\": " + seeds));
    }

    public static String source() {
        try {
            return new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
