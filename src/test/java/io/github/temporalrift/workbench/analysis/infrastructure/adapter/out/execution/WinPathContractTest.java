package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.game.WinPath;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.shared.game.EndReason;

/** The adapter converts a case result's win type to a win path by name, so every win type must have one. */
class WinPathContractTest {

    @Test
    void everyWinTypeIsAWinPathOfTheSameName() {
        assertThat(Arrays.stream(WinType.values()).map(WinType::name))
                .allSatisfy(name -> assertThat(WinPath.valueOf(name).name()).isEqualTo(name));
    }

    @Test
    void onlyTheSpecialEndingsHaveAWinPathOfTheirOwn() {
        assertThat(WinPath.ofSpecialEnding(EndReason.TIMELINE_STABILIZED)).isEqualTo(WinPath.TIMELINE_STABILIZED);
        assertThat(WinPath.ofSpecialEnding(EndReason.TIMELINE_COLLAPSED)).isEqualTo(WinPath.TIMELINE_COLLAPSED);
        assertThat(WinPath.ofSpecialEnding(EndReason.WIN_CONDITION_MET)).isNull();
    }
}
