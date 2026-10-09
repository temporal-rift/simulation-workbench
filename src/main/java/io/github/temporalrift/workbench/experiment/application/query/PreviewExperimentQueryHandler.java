package io.github.temporalrift.workbench.experiment.application.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.temporalrift.workbench.experiment.application.port.in.PreviewExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewMatrixUseCase;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService.CaseCoordinate;

/** Pages the matrix the freeze and the run would enumerate, so a preview can never disagree with them. */
public class PreviewExperimentQueryHandler implements PreviewExperimentUseCase {

    private final PreviewMatrixUseCase matrix;

    public PreviewExperimentQueryHandler(PreviewMatrixUseCase matrix) {
        this.matrix = matrix;
    }

    @Override
    public Result handle(Query query) {
        var cases = matrix.handle(query.manifest());
        var from = Math.min(query.offset(), cases.size());
        var to = Math.min(from + query.limit(), cases.size());
        return new Result(
                ManifestDigest.sha256Hex(query.manifest()),
                cases.size(),
                breakdown(cases),
                cases.subList(from, to),
                query.limit(),
                query.offset());
    }

    /** Variants in the order the matrix first meets them, each with its player counts ascending. */
    private static List<Breakdown> breakdown(List<CaseCoordinate> cases) {
        Map<String, Map<Integer, Integer>> counts = new LinkedHashMap<>();
        cases.forEach(coordinate -> counts.computeIfAbsent(coordinate.variantLabel(), variant -> new TreeMap<>())
                .merge(coordinate.playerCount(), 1, Integer::sum));
        var rows = new ArrayList<Breakdown>();
        counts.forEach((variant, byPlayers) ->
                byPlayers.forEach((players, count) -> rows.add(new Breakdown(variant, players, count))));
        return rows;
    }
}
