package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;

class WindowKeysTest {

    @Test
    void everyKeyThePolicyPackageAssignsReadsBackToTheProjectionCoordinates() {
        assertThat(WindowKeys.parse(new DecisionWindow.HandSelection(2, List.of(), 5).key()))
                .isEqualTo(new WindowKeys.Ref("HAND_SELECTION", 2, null));
        assertThat(WindowKeys.parse(new DecisionWindow.Declaration(3, List.of()).key()))
                .isEqualTo(new WindowKeys.Ref("DECLARATION", 3, null));
        assertThat(WindowKeys.parse(new DecisionWindow.ActionRound(4, 2, List.of(), List.of(), List.of()).key()))
                .isEqualTo(new WindowKeys.Ref("ACTION", 4, 2));
        assertThat(WindowKeys.parse(new DecisionWindow.ParadoxResolution(5, List.of()).key()))
                .isEqualTo(new WindowKeys.Ref("PARADOX_RESOLUTION", 5, null));
    }

    @Test
    void anUnknownKeyIsAContractMismatch() {
        assertThatThrownBy(() -> WindowKeys.parse("era1/mystery")).isInstanceOf(AttemptFailedException.class);
        assertThatThrownBy(() -> WindowKeys.parse("lobby")).isInstanceOf(AttemptFailedException.class);
    }
}
