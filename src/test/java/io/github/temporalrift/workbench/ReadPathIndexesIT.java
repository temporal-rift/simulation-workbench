package io.github.temporalrift.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@WorkbenchIntegrationTest
class ReadPathIndexesIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theComparisonListingAndTheLaneAttemptReadHaveIndexesInTheirSortOrder() {
        var expected = Map.of(
                "analysis_comparison_by_baseline_idx", "(baseline_run_id, created_at DESC, comparison_id DESC)",
                "analysis_comparison_by_candidate_idx", "(candidate_run_id, created_at DESC, comparison_id DESC)",
                "analysis_comparison_by_created_idx", "(created_at DESC, comparison_id DESC)",
                "case_attempt_by_lane_started_idx", "(lane_id, started_at DESC)");

        expected.forEach((name, columns) -> assertThat(
                        jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, name))
                .singleElement()
                .asString()
                .endsWith(columns));
    }
}
