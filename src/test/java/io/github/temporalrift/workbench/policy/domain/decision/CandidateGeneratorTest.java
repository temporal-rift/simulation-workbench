package io.github.temporalrift.workbench.policy.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;

class CandidateGeneratorTest {

    @Test
    void handSelectionEnumeratesEveryFiveOfSevenSubset() {
        var candidates = CandidateGenerator.generate(PolicyFixtures.handSelection(Faction.ERASERS));

        assertThat(candidates).hasSize(21).allMatch(c -> c instanceof Candidate.KeepHand);
        assertThat(candidates)
                .extracting(c -> ((Candidate.KeepHand) c).cardInstanceIds().size())
                .containsOnly(5);
    }

    @Test
    void declarationOffersEveryOutcomeAndDecline() {
        var candidates = CandidateGenerator.generate(PolicyFixtures.declaration(Faction.ACTIVISTS));

        assertThat(candidates.stream().filter(c -> c instanceof Candidate.Declare))
                .hasSize(9);
        assertThat(candidates).last().isInstanceOf(Candidate.Decline.class);
    }

    @Test
    void actionRoundCoversEveryTargetShape() {
        var candidates = CandidateGenerator.generate(PolicyFixtures.actionRound(Faction.ERASERS));

        var targets = candidates.stream()
                .filter(c -> c instanceof Candidate.PlayCard)
                .map(c -> ((Candidate.PlayCard) c).target().getClass().getSimpleName())
                .distinct()
                .toList();
        assertThat(targets)
                .containsExactlyInAnyOrder("EventOutcome", "OutcomePair", "Events", "Disguise", "Player", "Players");
        assertThat(candidates.stream().filter(c -> c instanceof Candidate.PlaySpecial))
                .hasSize(9 + 3 + 9);
        assertThat(candidates).last().isInstanceOf(Candidate.Pass.class);
    }

    @Test
    void listTargetsHaveTheRequiredSizeAndDistinctMembers() {
        var candidates = CandidateGenerator.generate(PolicyFixtures.actionRound(Faction.PROPHETS));

        var events = candidates.stream()
                .filter(c -> c instanceof Candidate.PlayCard play && play.target() instanceof Target.Events)
                .map(c -> (Target.Events) ((Candidate.PlayCard) c).target())
                .toList();
        var players = candidates.stream()
                .filter(c -> c instanceof Candidate.PlayCard play && play.target() instanceof Target.Players)
                .map(c -> (Target.Players) ((Candidate.PlayCard) c).target())
                .toList();
        assertThat(events)
                .hasSize(3)
                .allMatch(t -> t.eventIds().stream().distinct().count() == 2);
        assertThat(players)
                .hasSize(3)
                .allMatch(t -> t.playerIds().stream().distinct().count() == 2);
    }

    @Test
    void emptyParadoxOfferHasOnlyPass() {
        var candidates = CandidateGenerator.generate(PolicyFixtures.paradox(Faction.WEAVERS, true));

        assertThat(candidates).containsExactly(new Candidate.PassParadox());
    }

    @Test
    void terminalReadinessHasOnlyConfirm() {
        assertThat(CandidateGenerator.generate(PolicyFixtures.terminal(Faction.WEAVERS)))
                .containsExactly(new Candidate.ConfirmReady());
    }

    @Test
    void orderingIsCanonicalAndStable() {
        var first = CandidateGenerator.generate(PolicyFixtures.actionRound(Faction.REVISIONISTS));
        var second = CandidateGenerator.generate(PolicyFixtures.actionRound(Faction.REVISIONISTS));

        assertThat(first).isEqualTo(second).doesNotHaveDuplicates();
        assertThat(first.stream().mapToInt(Candidate::rank).boxed().toList()).isSorted();
    }
}
