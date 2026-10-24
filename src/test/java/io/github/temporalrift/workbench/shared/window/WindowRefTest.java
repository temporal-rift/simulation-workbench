package io.github.temporalrift.workbench.shared.window;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class WindowRefTest {

    @Test
    void anActionRoundNeedsItsRound() {
        assertThat(WindowRef.actionRound(1, 2)).isEqualTo(new WindowRef(WindowKind.ACTION_ROUND, 1, 2));
        assertThatThrownBy(() -> new WindowRef(WindowKind.ACTION_ROUND, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACTION_ROUND")
                .hasMessageContaining("null");
    }

    @Test
    void everyOtherKindRefusesARound() {
        assertThatThrownBy(() -> new WindowRef(WindowKind.HAND_SELECTION, 1, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HAND_SELECTION")
                .hasMessageContaining("2");
    }

    @Test
    void windowsAreOrderedByEraThenKindThenRound() {
        var ordered = List.of(
                WindowRef.of(WindowKind.HAND_SELECTION, 1),
                WindowRef.of(WindowKind.DECLARATION, 1),
                WindowRef.actionRound(1, 1),
                WindowRef.actionRound(1, 2),
                WindowRef.of(WindowKind.PARADOX_RESOLUTION, 1),
                WindowRef.of(WindowKind.TERMINAL_READINESS, 1),
                WindowRef.of(WindowKind.HAND_SELECTION, 2));

        assertThat(ordered.reversed().stream().sorted()).containsExactlyElementsOf(ordered);
    }

    @Test
    void theLabelNamesTheEraRoundAndKind() {
        assertThat(WindowRef.actionRound(1, 2).label()).isEqualTo("era1/round2/action");
        assertThat(WindowRef.of(WindowKind.PARADOX_RESOLUTION, 3).label()).isEqualTo("era3/paradox-resolution");
    }
}
