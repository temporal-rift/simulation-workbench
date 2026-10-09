package io.github.temporalrift.workbench.experiment.application.port.in;

import java.util.List;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService.CaseCoordinate;

/** Previews a manifest's matrix exactly as freezing and running it would produce it, persisting nothing. */
public interface PreviewExperimentUseCase {

    Result handle(Query query);

    record Query(JsonNode manifest, int limit, int offset) {}

    /** The digest the manifest would freeze under, the whole matrix's size, and the requested page of cases. */
    record Result(
            String manifestDigest,
            int caseCount,
            List<Breakdown> breakdown,
            List<CaseCoordinate> cases,
            int limit,
            int offset) {

        public Result {
            breakdown = List.copyOf(breakdown);
            cases = List.copyOf(cases);
        }
    }

    record Breakdown(String variantLabel, int playerCount, int caseCount) {}
}
