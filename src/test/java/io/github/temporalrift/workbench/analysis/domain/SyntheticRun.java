package io.github.temporalrift.workbench.analysis.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.fact.FactExtractor;
import io.github.temporalrift.workbench.analysis.domain.fact.GameFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.game.ParadoxType;
import io.github.temporalrift.workbench.analysis.domain.game.SeatPolicy;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * Synthetic cases of one variant, played by one policy on every seat or on a named table, with the facts of the
 * succeeded ones.
 */
public final class SyntheticRun {

    public static final List<Faction> EPW = List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS);

    private final String variant;
    private String policy;
    private String table;
    private List<String> tableSeats;
    private boolean paradoxes;
    private final List<AnalyzedCase> cases = new ArrayList<>();
    private final Map<UUID, CaseFacts> facts = new HashMap<>();

    private final long idSpace;

    public SyntheticRun(String variant, String policy) {
        this(variant, policy, 7);
    }

    /** @param idSpace keeps the case ids of this run apart from those of another synthetic run */
    public SyntheticRun(String variant, String policy, long idSpace) {
        this.variant = variant;
        this.policy = policy;
        this.idSpace = idSpace;
    }

    /** The policy of the cases added from now on. */
    public SyntheticRun policy(String policyId) {
        this.policy = policyId;
        this.table = null;
        this.tableSeats = null;
        return this;
    }

    /**
     * The table of the cases added from now on: its id, and the criterion reference of the policy each seat plays,
     * such as {@code random-v1}.
     */
    public SyntheticRun table(String tableId, List<String> seatPolicies) {
        this.table = tableId;
        this.tableSeats = List.copyOf(seatPolicies);
        return this;
    }

    /** From now on every game has two paradox findings, one of them cascaded. */
    public SyntheticRun withParadoxes() {
        this.paradoxes = true;
        return this;
    }

    public List<AnalyzedCase> cases() {
        return cases;
    }

    public Map<UUID, CaseFacts> facts() {
        return facts;
    }

    /** A game that ended with the given reason and winners (seats), in the given era. */
    public SyntheticRun game(String seed, List<Faction> seats, EndReason reason, Set<Integer> winners, int eras) {
        var scores = new ArrayList<Integer>();
        seats.forEach(seat -> scores.add(10));
        var outcome = new CaseOutcome(reason, winners, scores, eras, eras * 3, eras * 9);
        var analyzed = analyzed(seed, seats, CaseState.SUCCEEDED, outcome, null);
        cases.add(analyzed);
        var extracted = FactExtractor.extract(
                analyzed.caseId(), seats, outcome, new GameRecord(List.of(), List.of(), List.of()));
        facts.put(analyzed.caseId(), paradoxes ? withParadoxes(extracted) : extracted);
        return this;
    }

    /** A normal victory in era 4. */
    public SyntheticRun win(String seed, List<Faction> seats, int winnerSeat) {
        return game(seed, seats, EndReason.WIN_CONDITION_MET, Set.of(winnerSeat), 4);
    }

    public SyntheticRun failed(String seed, List<Faction> seats, FailureCode code) {
        cases.add(analyzed(seed, seats, CaseState.FAILED, null, code));
        return this;
    }

    /** A case that has not finished, or was cancelled. */
    public SyntheticRun unfinished(String seed, List<Faction> seats, CaseState status) {
        cases.add(analyzed(seed, seats, status, null, null));
        return this;
    }

    /** A policy referred to as faction-greedy-v1 has id faction-greedy at version 1.0.0. */
    private String policyId() {
        return policy.replaceFirst("-v1$", "");
    }

    /** A criterion reference such as {@code random-v2} as the policy {@code random} at version {@code 2.0.0}. */
    private static SeatPolicy seatPolicy(String reference) {
        var split = reference.lastIndexOf("-v");
        return new SeatPolicy(reference.substring(0, split), reference.substring(split + 2) + ".0.0");
    }

    private static CaseFacts withParadoxes(CaseFacts extracted) {
        var game = extracted.game();
        return new CaseFacts(
                extracted.caseId(),
                new GameFacts(
                        game.endReason(),
                        game.winners(),
                        game.eras(),
                        game.rounds(),
                        game.decisions(),
                        Map.of(ParadoxType.DEAD_HEAT, 2),
                        2,
                        1,
                        1),
                extracted.seats());
    }

    private AnalyzedCase analyzed(
            String seed, List<Faction> seats, CaseState status, CaseOutcome outcome, FailureCode code) {
        var seatPolicies = table == null
                ? SeatPolicies.uniform(policyId(), "1.0.0", seats.size())
                : tableSeats.subList(0, seats.size()).stream()
                        .map(SyntheticRun::seatPolicy)
                        .toList();
        return new AnalyzedCase(
                new UUID(idSpace, cases.size()),
                variant,
                table == null ? policyId() : table,
                seed,
                seats.size(),
                seatPolicies,
                seats,
                status,
                outcome,
                code);
    }
}
