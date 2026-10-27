package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.in.rest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetSimulatorFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCaseResult;
import io.github.temporalrift.workbench.fidelity.domain.FidelityEnvironment;
import io.github.temporalrift.workbench.fidelity.domain.FidelityFailure;
import io.github.temporalrift.workbench.fidelity.domain.FidelityVariant;
import io.github.temporalrift.workbench.fidelity.domain.FirstDivergence;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Divergence;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Failure;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCase.PlayerCountEnum;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCheck;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCheck.VerdictEnum;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCounts;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityVariantLabel;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ServiceProvenance;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ServiceProvenanceMap;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SimulatorFidelity;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SimulatorFidelitySourceRevisions;

/** Maps fidelity records to the published {@code simulation-api} representations and back. */
final class FidelityApiMapper {

    private FidelityApiMapper() {}

    static FidelityCheck toApi(FidelityCheckView view) {
        var check = view.check();
        return new FidelityCheck(
                check.id(),
                check.seed(),
                io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCheckState
                        .valueOf(check.state().name()),
                check.verdict() == null
                        ? null
                        : VerdictEnum.valueOf(check.verdict().name()),
                environment(check.simulator()),
                environment(check.lane()),
                check.variants().stream().map(FidelityApiMapper::variant).toList(),
                counts(view.counts()),
                failure(check.failure()),
                offset(check.createdAt()),
                offset(check.startedAt()),
                offset(check.finishedAt()));
    }

    static SimulatorFidelity toApi(GetSimulatorFidelityUseCase.SimulatorFidelity fidelity) {
        var revisions = fidelity.sourceRevisions();
        return new SimulatorFidelity(
                new SimulatorFidelitySourceRevisions(
                        revisions.gameService(), revisions.timelineService(), revisions.readService()),
                fidelity.established(),
                fidelity.record() == null ? null : toApi(fidelity.record()));
    }

    static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCase toApi(
            FidelityCase fidelityCase) {
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCase(
                fidelityCase.caseKey(),
                label(fidelityCase.variant()),
                PlayerCountEnum.fromValue(fidelityCase.playerCount()),
                fidelityCase.factionSet().stream()
                        .map(
                                faction -> io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1
                                        .model.Faction.valueOf(faction.name()))
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new)),
                fidelityCase.seatRotation(),
                fidelityCase.policyId(),
                io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCaseResult
                        .valueOf(fidelityCase.result().name()),
                divergence(fidelityCase.firstDivergence()),
                failure(fidelityCase.failure()));
    }

    static VariantLabel domain(FidelityVariantLabel label) {
        return label == null ? null : VariantLabel.ofLabel(label.getValue());
    }

    static FidelityCaseResult domain(
            io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCaseResult result) {
        return result == null ? null : FidelityCaseResult.valueOf(result.getValue());
    }

    static io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState domain(
            io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCheckState state) {
        return state == null
                ? null
                : io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState.valueOf(state.getValue());
    }

    private static FidelityVariantLabel label(VariantLabel label) {
        return FidelityVariantLabel.fromValue(label.label());
    }

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityEnvironment
            environment(FidelityEnvironment environment) {
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityEnvironment(
                new ServiceProvenanceMap(
                        service(environment.gameService()),
                        service(environment.timelineService()),
                        service(environment.readService())),
                environment.contentDigest(),
                new LinkedHashMap<>(environment.contracts()));
    }

    private static ServiceProvenance service(FidelityEnvironment.ServiceBuild build) {
        return new ServiceProvenance(build.imageDigest(), build.sourceRevision());
    }

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityVariant
            variant(FidelityVariant variant) {
        Map<String, BigDecimal> overrides = new LinkedHashMap<>(variant.overrides());
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityVariant(
                label(variant.label()), variant.effectiveRulesDigest(), overrides);
    }

    private static FidelityCounts counts(io.github.temporalrift.workbench.fidelity.domain.FidelityCounts counts) {
        return new FidelityCounts(
                counts.total(), counts.matched(), counts.diverged(), counts.unreproducible(), counts.pending());
    }

    private static Failure failure(FidelityFailure failure) {
        return failure == null ? null : new Failure(failure.code(), failure.message());
    }

    private static Divergence divergence(FirstDivergence divergence) {
        return divergence == null
                ? null
                : new Divergence(divergence.step(), divergence.kind(), divergence.expected(), divergence.actual());
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
