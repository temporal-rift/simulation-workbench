package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.fact.FactExtractor;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CardGrade;
import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.CardType;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.CaseStatus;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.game.SpecialAction;

class ReportScaleTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String DIGEST = "b".repeat(64);
    private static final int SEEDS = 6;

    @Test
    void groupingCohortsInOnePassGivesTheReportOfFilteringTheCasesPerCohort() {
        var cases = new ArrayList<AnalyzedCase>();
        var facts = new HashMap<UUID, CaseFacts>();
        for (var seed = 1; seed <= SEEDS; seed++) {
            for (var size = 3; size <= 5; size++) {
                for (var set : factionSets(size)) {
                    for (var rotation = 0; rotation < size; rotation++) {
                        var seats = new ArrayList<>(set);
                        Collections.rotate(seats, rotation);
                        var outcome = new CaseOutcome(
                                EndReason.WIN_CONDITION_MET,
                                Set.of((seed + rotation) % size),
                                java.util.Collections.nCopies(size, 10),
                                3,
                                9,
                                30);
                        var analyzed = new AnalyzedCase(
                                new UUID(seed, cases.size()),
                                "base",
                                String.valueOf(seed),
                                size,
                                "random",
                                "1.0.0",
                                seats,
                                CaseStatus.SUCCEEDED,
                                outcome);
                        cases.add(analyzed);
                        facts.put(
                                analyzed.caseId(),
                                FactExtractor.extract(analyzed.caseId(), seats, outcome, record(size)));
                    }
                }
            }
        }

        var report = ReportCalculator.report(RUN, DIGEST, AnalysisVersion.CURRENT, cases, facts);

        var vocabulary = Vocabulary.of(cases, facts.values());
        var failureCodes = ReportCalculator.failureCodes(cases);
        var expected = ReportCalculator.cohortKeys(cases).stream()
                .map(key -> CohortComputation.of(key, cases, facts, vocabulary, failureCodes, AnalysisVersion.CURRENT)
                        .cohort())
                .toList();
        assertThat(report.cohorts()).isEqualTo(expected);
    }

    /** Every seat is dealt every card and submits every special, so each cohort lists the full vocabulary. */
    private static GameRecord record(int seats) {
        var cards = new ArrayList<CardKey>();
        for (var type : CardType.values()) {
            for (var grade : CardGrade.values()) {
                cards.add(new CardKey(type, grade));
            }
        }
        var events = new ArrayList<GameEvent>();
        var submissions = new ArrayList<GameRecord.SpecialSubmission>();
        for (var seat = 0; seat < seats; seat++) {
            events.add(new GameEvent.HandDealt(seat, 1, cards));
            for (var action : SpecialAction.values()) {
                submissions.add(new GameRecord.SpecialSubmission(seat, action, false));
            }
        }
        return new GameRecord(events, List.of(), submissions);
    }

    private static List<List<Faction>> factionSets(int size) {
        var all = new ArrayList<>(List.of(Faction.values()));
        all.sort(java.util.Comparator.comparing(Faction::name));
        var sets = new ArrayList<List<Faction>>();
        combine(all, size, 0, new ArrayList<>(), sets);
        return sets;
    }

    private static void combine(List<Faction> all, int size, int from, List<Faction> current, List<List<Faction>> out) {
        if (current.size() == size) {
            out.add(List.copyOf(current));
            return;
        }
        for (var index = from; index < all.size(); index++) {
            current.add(all.get(index));
            combine(all, size, index + 1, current, out);
            current.removeLast();
        }
    }
}
