package io.github.temporalrift.workbench.policy.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase.DecisionResult;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase.SeatPolicy;
import io.github.temporalrift.workbench.policy.domain.baseline.BaselinePolicies;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.Reconciliation;
import io.github.temporalrift.workbench.policy.domain.decision.ReconciliationPendingException;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

class DecisionWindowServiceTest {

    private static final int BUDGET = 3;

    @Test
    void everySeatIsObservedAndDecidedBeforeAnySubmission() {
        var gateway = new FakeGateway();
        gateway.observations.put(0, PolicyFixtures.actionRound(Faction.ERASERS));
        gateway.observations.put(1, withSeat(PolicyFixtures.actionRound(Faction.PROPHETS), 1));
        var decided = new ArrayList<String>();
        BotPolicy recording = (observation, excluded, entropy) -> {
            decided.add("decide" + observation.seatIndex() + ":submissionsSoFar=" + gateway.submissions.size());
            return BaselinePolicies.RANDOM_V1.policy().decide(observation, excluded, entropy);
        };

        new DecisionWindowService(gateway)
                .play(List.of(new SeatPolicy(1, recording, 5), new SeatPolicy(0, recording, 5)), BUDGET);

        assertThat(decided).containsExactly("decide0:submissionsSoFar=0", "decide1:submissionsSoFar=0");
        assertThat(gateway.log).startsWith("observe0", "observe1");
        assertThat(gateway.submissions.keySet()).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void rejectedCandidateIsExcludedAndReselectedFromRefreshedObservation() {
        var gateway = new FakeGateway();
        gateway.observations.put(0, PolicyFixtures.actionRound(Faction.ERASERS));
        gateway.rejectFirst = 1;

        var result = play(gateway, BaselinePolicies.FACTION_GREEDY_V1.policy());

        var submitted = (DecisionResult.Submitted) result;
        assertThat(submitted.rejectionCodes()).containsExactly("422-03");
        assertThat(gateway.submitted).hasSize(2);
        assertThat(gateway.submitted.get(1)).isNotEqualTo(gateway.submitted.get(0));
        assertThat(gateway.log.stream().filter("observe0"::equals)).hasSize(2);
    }

    @Test
    void budgetExhaustionFallsBackToAnAvailablePass() {
        var gateway = new FakeGateway();
        gateway.observations.put(0, PolicyFixtures.actionRound(Faction.ERASERS));
        gateway.rejectAllExceptFallbacks = true;

        var result = play(gateway, BaselinePolicies.FACTION_GREEDY_V1.policy());

        var submitted = (DecisionResult.Submitted) result;
        assertThat(submitted.candidate()).isEqualTo(new Candidate.Pass());
        assertThat(submitted.rejectionCodes()).hasSize(BUDGET);
    }

    @Test
    void exhaustionWithoutPassReturnsPolicyExhausted() {
        var gateway = new FakeGateway();
        gateway.observations.put(0, PolicyFixtures.terminal(Faction.ERASERS));
        gateway.rejectFirst = 5;

        var result = play(gateway, BaselinePolicies.RANDOM_V1.policy());

        assertThat(result).isInstanceOf(DecisionResult.PolicyExhausted.class);
        assertThat(((DecisionResult.PolicyExhausted) result).rejectionCodes()).hasSize(1);
    }

    @Test
    void rejectedOnlyOptionExhaustsWithoutRetry() {
        var gateway = new FakeGateway();
        gateway.observations.put(0, PolicyFixtures.paradox(Faction.PROPHETS, true));
        gateway.rejectFirst = 5;

        var result = play(gateway, BaselinePolicies.RANDOM_V1.policy());

        assertThat(result).isInstanceOf(DecisionResult.PolicyExhausted.class);
    }

    @Test
    void lostResponseForAnAcceptedSubmissionIsRecoveredWithoutResubmitting() {
        var gateway = new FakeGateway();
        gateway.observations.put(0, PolicyFixtures.actionRound(Faction.ERASERS));
        gateway.loseResponse = true;

        var result = play(gateway, BaselinePolicies.RANDOM_V1.policy());

        var submitted = (DecisionResult.Submitted) result;
        assertThat(submitted.recovered()).isTrue();
        assertThat(gateway.submitted).hasSize(1);
        assertThat(gateway.acceptedCount).isEqualTo(1);
        assertThat(submitted.candidate()).isEqualTo(gateway.submitted.getFirst());
    }

    @Test
    void lostResponseForAnUnacceptedSubmissionIsResubmittedOnce() {
        var gateway = new FakeGateway();
        gateway.observations.put(0, PolicyFixtures.actionRound(Faction.ERASERS));
        gateway.loseResponse = true;
        gateway.lostSubmissionIsNotAccepted = true;

        var result = play(gateway, BaselinePolicies.RANDOM_V1.policy());

        assertThat(((DecisionResult.Submitted) result).recovered()).isFalse();
        assertThat(gateway.submitted).hasSize(2).containsOnly(gateway.submitted.getFirst());
        assertThat(gateway.acceptedCount).isEqualTo(1);
    }

    @Test
    void pendingAcceptedStateNeverTriggersAResubmission() {
        var gateway = new FakeGateway();
        gateway.observations.put(0, PolicyFixtures.actionRound(Faction.ERASERS));
        gateway.loseResponse = true;
        gateway.pendingReconciliation = true;

        assertThatThrownBy(() -> play(gateway, BaselinePolicies.RANDOM_V1.policy()))
                .isInstanceOf(ReconciliationPendingException.class);
        assertThat(gateway.submitted).hasSize(1);
    }

    private static DecisionResult play(FakeGateway gateway, BotPolicy policy) {
        return new DecisionWindowService(gateway)
                .play(List.of(new SeatPolicy(0, policy, 99)), BUDGET)
                .getFirst()
                .result();
    }

    private static EntitledObservation withSeat(EntitledObservation observation, int seat) {
        return new EntitledObservation(
                seat, observation.faction(), observation.events(), observation.otherPlayerIds(), observation.window());
    }

    /** In-memory participant gateway with scriptable rejections and lost responses. */
    private static final class FakeGateway implements ParticipantGateway {
        final Map<Integer, EntitledObservation> observations = new HashMap<>();
        final Map<Integer, Candidate> submissions = new HashMap<>();
        final List<Candidate> submitted = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        int rejectFirst;
        boolean rejectAllExceptFallbacks;
        boolean loseResponse;
        boolean lostSubmissionIsNotAccepted;
        boolean pendingReconciliation;
        int acceptedCount;
        private boolean lostOnce;

        @Override
        public EntitledObservation observe(int seatIndex) {
            log.add("observe" + seatIndex);
            return observations.get(seatIndex);
        }

        @Override
        public SubmissionOutcome submit(int seatIndex, Candidate candidate) {
            log.add("submit" + seatIndex);
            submitted.add(candidate);
            if (rejectFirst > 0) {
                rejectFirst--;
                return new SubmissionOutcome.Rejected("422-03");
            }
            if (rejectAllExceptFallbacks && !candidate.isFallback()) {
                return new SubmissionOutcome.Rejected("422-03");
            }
            if (loseResponse && !lostOnce) {
                lostOnce = true;
                if (!lostSubmissionIsNotAccepted) {
                    accept(seatIndex, candidate);
                }
                return new SubmissionOutcome.Unacknowledged();
            }
            accept(seatIndex, candidate);
            return new SubmissionOutcome.Accepted();
        }

        private void accept(int seatIndex, Candidate candidate) {
            acceptedCount++;
            submissions.put(seatIndex, candidate);
        }

        @Override
        public Reconciliation reconcile(int seatIndex) {
            if (pendingReconciliation) {
                return new Reconciliation.Pending();
            }
            var accepted = submissions.get(seatIndex);
            return accepted == null ? new Reconciliation.NotAccepted() : new Reconciliation.Accepted(accepted);
        }
    }
}
