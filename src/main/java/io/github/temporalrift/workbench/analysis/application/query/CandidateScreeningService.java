package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.CandidateScreening;
import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.analysis.domain.evaluation.DeviationProfile;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluatedLine;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationMode;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationVerdict;
import io.github.temporalrift.workbench.analysis.domain.evaluation.LineVerdict;
import io.github.temporalrift.workbench.analysis.domain.evaluation.RunEvaluation;
import io.github.temporalrift.workbench.analysis.domain.evaluation.RunEvaluator;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;

/**
 * Screens candidates whose games may lie in several runs. A candidate is evaluated exactly as a run variant is, over
 * the union of its cases, and two candidates are compared on the seeds they share.
 */
public class CandidateScreeningService implements CandidateScreening {

    private final RunSource runs;
    private final BalanceCriteria criteria;
    private final CaseFactsProvider facts;

    public CandidateScreeningService(RunSource runs, BalanceCriteria criteria, CaseFactsProvider facts) {
        this.runs = runs;
        this.criteria = criteria;
        this.facts = facts;
    }

    @Override
    public Screening screen(int criterionVersion, List<Candidate> candidates) {
        var criterion = criteria.find(criterionVersion)
                .orElseThrow(() -> new IllegalArgumentException("Unknown balance criterion " + criterionVersion));
        var analysis = new AnalysisVersion(criterion.analysisVersion(), AnalysisVersion.CURRENT.seed());
        var evaluations = new HashMap<String, Evaluation>();
        var profiles = new TreeMap<String, DeviationProfile>();
        for (var candidate : candidates) {
            var played = played(candidate, analysis);
            var evaluation = RunEvaluator.evaluate(
                    candidate.games().getFirst().runId(),
                    candidate.id(),
                    criterion,
                    EvaluationMode.SCREENING,
                    analysis,
                    played.cases(),
                    played.facts());
            evaluations.put(candidate.id(), summary(evaluation));
            if (evaluation.verdict() == EvaluationVerdict.UNCONFIRMED) {
                profiles.put(candidate.id(), DeviationProfile.of(criterion, played.cases(), played.facts(), analysis));
            }
        }
        return new Screening(evaluations, dominators(profiles));
    }

    /** The cases and the facts of the succeeded ones of one candidate across its runs. */
    private record Played(List<AnalyzedCase> cases, Map<UUID, CaseFacts> facts) {}

    private Played played(Candidate candidate, AnalysisVersion analysis) {
        if (candidate.games().isEmpty()) {
            throw new IllegalArgumentException("Candidate " + candidate.id() + " has no games");
        }
        var cases = new ArrayList<AnalyzedCase>();
        var caseFacts = new HashMap<UUID, CaseFacts>();
        for (var source : candidate.games()) {
            var ofVariant = runs.cases(source.runId()).stream()
                    .filter(played -> played.variantLabel().equals(source.variantLabel()))
                    .toList();
            if (ofVariant.isEmpty()) {
                throw new IllegalArgumentException(
                        "Candidate " + candidate.id() + " has no cases in variant " + source.variantLabel());
            }
            cases.addAll(ofVariant);
            caseFacts.putAll(facts.factsOf(source.runId(), ofVariant, analysis));
        }
        return new Played(cases, caseFacts);
    }

    private static Map<String, List<String>> dominators(Map<String, DeviationProfile> profiles) {
        var dominators = new HashMap<String, List<String>>();
        profiles.forEach((worse, worseProfile) -> {
            var found = new ArrayList<String>();
            profiles.forEach((better, betterProfile) -> {
                if (!better.equals(worse) && worseProfile.dominatedBy(betterProfile)) {
                    found.add(better);
                }
            });
            if (!found.isEmpty()) {
                dominators.put(worse, found);
            }
        });
        return dominators;
    }

    private static Evaluation summary(RunEvaluation evaluation) {
        var failing = evaluation.lines().stream()
                .filter(line -> line.role() == CriterionRole.GATE && line.verdict() == LineVerdict.FAILS)
                .map(EvaluatedLine::lineId)
                .distinct()
                .sorted()
                .toList();
        return new Evaluation(Verdict.valueOf(evaluation.verdict().name()), evaluation.score(), failing);
    }
}
