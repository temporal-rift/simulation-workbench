package io.github.temporalrift.workbench.search.infrastructure.adapter.out.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.CandidateScreening;
import io.github.temporalrift.workbench.analysis.RunConfirmation;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyRef;
import io.github.temporalrift.workbench.search.domain.campaign.SeatTable;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;

/** Evaluates candidates through the analysis module. */
public class CandidateEvaluationAdapter implements CandidateEvaluation {

    private final CandidateScreening screening;
    private final RunConfirmation confirmation;

    public CandidateEvaluationAdapter(CandidateScreening screening, RunConfirmation confirmation) {
        this.screening = screening;
        this.confirmation = confirmation;
    }

    @Override
    public int confirmationSeedCount(int criterionVersion) {
        return confirmation.minimumSeeds(criterionVersion);
    }

    @Override
    public List<PolicyRef> confirmationPolicies(int criterionVersion) {
        return confirmation.policies(criterionVersion).stream()
                .map(policy -> new PolicyRef(policy.id(), policy.majorVersion()))
                .toList();
    }

    @Override
    public List<SeatTable> screeningTables(int criterionVersion) {
        return seatTables(confirmation.screeningTables(criterionVersion));
    }

    @Override
    public List<SeatTable> confirmationTables(int criterionVersion) {
        return seatTables(confirmation.confirmationTables(criterionVersion));
    }

    private static List<SeatTable> seatTables(List<RunConfirmation.Table> tables) {
        return tables.stream()
                .map(table -> new SeatTable(
                        table.id(),
                        table.seats().stream()
                                .map(policy -> new PolicyRef(policy.id(), policy.majorVersion()))
                                .toList()))
                .toList();
    }

    @Override
    public Evaluation confirm(int criterionVersion, UUID runId, String variantLabel) {
        var result = confirmation.confirm(criterionVersion, runId, variantLabel);
        return new Evaluation(result.verdict(), result.score(), List.of(), null);
    }

    @Override
    public boolean servesCriterion(int criterionVersion) {
        return screening.servesCriterion(criterionVersion);
    }

    @Override
    public Screening screen(int criterionVersion, List<CandidateGames> candidates) {
        var screened = screening.screen(
                criterionVersion,
                candidates.stream()
                        .map(candidate -> new CandidateScreening.Candidate(
                                candidate.candidateId(),
                                candidate.games().stream()
                                        .map(games -> new CandidateScreening.Games(games.runId(), games.variantLabel()))
                                        .toList()))
                        .toList());
        var evaluations = new HashMap<String, Evaluation>();
        screened.evaluations()
                .forEach((id, evaluation) -> evaluations.put(
                        id,
                        new Evaluation(
                                evaluation.verdict().name(),
                                evaluation.score(),
                                evaluation.failingGateLineIds(),
                                evaluation.gateMiss())));
        return new Screening(evaluations, screened.dominators());
    }
}
