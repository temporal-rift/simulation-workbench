package io.github.temporalrift.workbench.fidelity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.game.Faction;

class FidelityMatrixTest {

    private static FidelityCase aCase(
            VariantLabel variant, int players, List<Faction> set, int rotation, String policyId) {
        return FidelityCase.pending(0, UUID.randomUUID(), variant, players, set, rotation, policyId, UUID.randomUUID());
    }

    @Test
    void thereAre55BaseCasesPerPolicyAndVariant() {
        var sets = FidelityMatrix.factionSets();

        assertThat(sets).hasSize(16);
        assertThat(sets.stream().mapToInt(List::size).sum()).isEqualTo(55);
        assertThat(FidelityMatrix.caseCount()).isEqualTo(330);
    }

    @Test
    void everySetIsOrderedByFactionNameAndDistinct() {
        var sets = FidelityMatrix.factionSets();

        assertThat(sets).doesNotHaveDuplicates();
        sets.forEach(set -> assertThat(set.stream().map(Faction::name).toList()).isSorted());
    }

    @Test
    void casesAreOrderedByVariantPlayerCountFactionSetRotationAndPolicy() {
        var sets = FidelityMatrix.factionSets();
        var three = sets.getFirst();
        var cases = new ArrayList<FidelityCase>(List.of(
                aCase(VariantLabel.HIGH, 3, three, 0, "faction-greedy"),
                aCase(VariantLabel.BASELINE, 4, sets.get(10), 0, "faction-greedy"),
                aCase(VariantLabel.BASELINE, 3, three, 1, "faction-greedy"),
                aCase(VariantLabel.BASELINE, 3, three, 0, "random"),
                aCase(VariantLabel.BASELINE, 3, three, 0, "faction-greedy"),
                aCase(VariantLabel.LOW, 3, three, 0, "faction-greedy"),
                aCase(VariantLabel.BASELINE, 3, sets.get(1), 0, "faction-greedy")));

        cases.sort(FidelityMatrix.ORDER);

        assertThat(cases)
                .extracting(
                        FidelityCase::variant,
                        FidelityCase::playerCount,
                        FidelityCase::seatRotation,
                        FidelityCase::policyId)
                .containsExactly(
                        tuple(VariantLabel.BASELINE, 3, 0, "faction-greedy"),
                        tuple(VariantLabel.BASELINE, 3, 0, "random"),
                        tuple(VariantLabel.BASELINE, 3, 1, "faction-greedy"),
                        tuple(VariantLabel.BASELINE, 3, 0, "faction-greedy"),
                        tuple(VariantLabel.BASELINE, 4, 0, "faction-greedy"),
                        tuple(VariantLabel.LOW, 3, 0, "faction-greedy"),
                        tuple(VariantLabel.HIGH, 3, 0, "faction-greedy"));
        assertThat(cases.get(3).factionSet()).isEqualTo(sets.get(1));
    }
}
