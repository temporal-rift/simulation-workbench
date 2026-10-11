package io.github.temporalrift.workbench.experiment.application.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.temporalrift.workbench.experiment.application.port.in.PreviewExperimentUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidator;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService.CaseCoordinate;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;

/**
 * Pages the matrix the freeze and the run enumerate, so a preview can never disagree with them. The whole
 * matrix is visited to count it, but only the requested window is kept.
 */
public class PreviewExperimentQueryHandler implements PreviewExperimentUseCase {

    private final PolicyReferenceVerifier policyVerifier;

    public PreviewExperimentQueryHandler(PolicyReferenceVerifier policyVerifier) {
        this.policyVerifier = policyVerifier;
    }

    @Override
    public Result handle(Query query) {
        ExperimentValidator.validate(query.manifest(), policyVerifier);
        var digest = ManifestDigest.sha256Hex(query.manifest());
        var window = new ArrayList<CaseCoordinate>();
        Map<String, Map<Integer, Integer>> counts = new LinkedHashMap<>();
        var position = new int[] {0};
        MatrixPreviewService.enumerate(query.manifest(), digest, coordinate -> {
            if (position[0] >= query.offset() && window.size() < query.limit()) {
                window.add(coordinate);
            }
            position[0]++;
            counts.computeIfAbsent(coordinate.variantLabel(), variant -> new TreeMap<>())
                    .merge(coordinate.playerCount(), 1, Integer::sum);
        });
        return new Result(digest, position[0], breakdown(counts), window, query.limit(), query.offset());
    }

    /** Variants in the order the matrix first meets them, each with its player counts ascending. */
    private static List<Breakdown> breakdown(Map<String, Map<Integer, Integer>> counts) {
        var rows = new ArrayList<Breakdown>();
        counts.forEach((variant, byPlayers) ->
                byPlayers.forEach((players, count) -> rows.add(new Breakdown(variant, players, count))));
        return rows;
    }
}
