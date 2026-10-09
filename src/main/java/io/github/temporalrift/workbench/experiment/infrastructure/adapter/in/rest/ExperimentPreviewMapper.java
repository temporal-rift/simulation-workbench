package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import io.github.temporalrift.workbench.experiment.application.port.in.PreviewExperimentUseCase;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService.CaseCoordinate;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExperimentPreview;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PreviewBreakdown;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PreviewCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SeatAssignment;

/** Maps a matrix preview to the published {@code simulation-api} representation. */
final class ExperimentPreviewMapper {

    private ExperimentPreviewMapper() {}

    static ExperimentPreview toApi(PreviewExperimentUseCase.Result preview) {
        return new ExperimentPreview(
                preview.manifestDigest(),
                preview.caseCount(),
                preview.breakdown().stream()
                        .map(row -> new PreviewBreakdown(
                                row.variantLabel(),
                                PreviewBreakdown.PlayerCountEnum.fromValue(row.playerCount()),
                                row.caseCount()))
                        .toList(),
                preview.cases().stream().map(ExperimentPreviewMapper::toApi).toList(),
                preview.limit(),
                preview.offset());
    }

    private static PreviewCase toApi(CaseCoordinate coordinate) {
        return new PreviewCase(
                coordinate.caseKey(),
                coordinate.variantLabel(),
                coordinate.seed(),
                PreviewCase.PlayerCountEnum.fromValue(coordinate.playerCount()),
                coordinate.seats().stream()
                        .map(seat -> new SeatAssignment(
                                seat.seatIndex(),
                                Faction.fromValue(seat.faction()),
                                seat.policyId(),
                                seat.policyVersion()))
                        .toList());
    }
}
