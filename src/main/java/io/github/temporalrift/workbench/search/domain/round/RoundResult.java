package io.github.temporalrift.workbench.search.domain.round;

import java.util.List;

/**
 * What a decided round left standing.
 *
 * @param survivors the candidates still in play, best first
 * @param shortList the best of the survivors, as far as the short list is long
 */
public record RoundResult(int round, List<Survivor> survivors, List<String> shortList) {

    public RoundResult {
        survivors = List.copyOf(survivors);
        shortList = List.copyOf(shortList);
    }

    /** A survivor and the evaluation that kept it. */
    public record Survivor(String candidateId, String verdict, Double score) {}

    public List<String> survivorIds() {
        return survivors.stream().map(Survivor::candidateId).toList();
    }
}
