package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.shared.Page;

class ExperimentSetupMapperTest {

    private static final LaneBundle.ServiceBuild BUILD =
            new LaneBundle.ServiceBuild("sha256:" + "e".repeat(64), "abc1234");

    @Test
    void anArtifactCarriesItsNameOnlyWhenTheLaneDeclaresOne() {
        var bundle = new LaneBundle(
                new LaneBundle.Artifact("c".repeat(64), "threshold-22"),
                new LaneBundle.Artifact("d".repeat(64), null),
                "a".repeat(64),
                "b".repeat(64),
                BUILD,
                BUILD,
                BUILD,
                new TreeMap<>(Map.of("session-api", "3.0.0")),
                2);

        var page = ExperimentSetupMapper.laneBundles(new Page<>(List.of(bundle), 1));

        var item = page.getItems().getFirst();
        assertThat(item.getRulesArtifact().getName()).isEqualTo("threshold-22");
        assertThat(item.getContentArtifact().getName()).isNull();
        assertThat(item.getServices().getReadService().getSourceRevision()).isEqualTo("abc1234");
        assertThat(item.getContracts()).containsEntry("session-api", "3.0.0");
        assertThat(item.getLaneCount()).isEqualTo(2);
        assertThat(page.getTotal()).isEqualTo(1);
    }
}
