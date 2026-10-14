package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.experiment.domain.template.ExperimentTemplate;
import io.github.temporalrift.workbench.shared.Page;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ArtifactReference;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExperimentDefaults;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExperimentTemplatePage;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.LaneBundlePage;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Policy;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SeatPolicyAssignment;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ServiceProvenance;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ServiceProvenanceMap;

/** Maps the guided-setup reads onto the published contract. */
final class ExperimentSetupMapper {

    private ExperimentSetupMapper() {}

    static LaneBundlePage laneBundles(Page<LaneBundle> page) {
        return new LaneBundlePage(
                page.items().stream().map(ExperimentSetupMapper::laneBundle).toList(), Math.toIntExact(page.total()));
    }

    static ExperimentTemplatePage templates(Page<ExperimentTemplate> page) {
        return new ExperimentTemplatePage(
                page.items().stream().map(ExperimentSetupMapper::template).toList(), Math.toIntExact(page.total()));
    }

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.LaneBundle
            laneBundle(LaneBundle bundle) {
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.LaneBundle(
                artifact(bundle.rulesArtifact()),
                artifact(bundle.contentArtifact()),
                bundle.effectiveRulesDigest(),
                bundle.effectiveContentDigest(),
                new ServiceProvenanceMap(
                        service(bundle.gameService()),
                        service(bundle.timelineService()),
                        service(bundle.readService())),
                new LinkedHashMap<>(bundle.contracts()),
                bundle.laneCount());
    }

    private static ArtifactReference artifact(LaneBundle.Artifact artifact) {
        return new ArtifactReference(artifact.digest()).name(artifact.name());
    }

    private static ServiceProvenance service(LaneBundle.ServiceBuild build) {
        return new ServiceProvenance(build.imageDigest(), build.sourceRevision());
    }

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExperimentTemplate
            template(ExperimentTemplate template) {
        var defaults = template.defaults();
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExperimentTemplate(
                template.templateId(),
                template.name(),
                template.description(),
                template.variantCount(),
                new ExperimentDefaults(
                        new LinkedHashSet<>(defaults.seeds()),
                        defaults.playerCounts().stream()
                                .map(ExperimentDefaults.PlayerCountsEnum::fromValue)
                                .collect(Collectors.toCollection(LinkedHashSet::new)),
                        defaults.factionSets().stream()
                                .map(set -> set.stream()
                                        .map(Faction::fromValue)
                                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                                .collect(Collectors.toCollection(LinkedHashSet::new)),
                        ExperimentDefaults.SeatRotationModeEnum.fromValue(defaults.seatRotationMode()),
                        ExperimentDefaults.TimingModeEnum.fromValue(defaults.timingMode()),
                        defaults.policies().stream()
                                .map(ExperimentSetupMapper::policy)
                                .toList(),
                        defaults.concurrency(),
                        defaults.caseWallTimeoutSeconds(),
                        defaults.maxRejectedCandidatesPerWindow()));
    }

    private static Policy policy(ExperimentTemplate.Policy policy) {
        return new Policy(
                policy.id(),
                policy.version(),
                policy.artifactDigest(),
                Map.of(),
                policy.seats().stream()
                        .map(seat -> new SeatPolicyAssignment(seat, policy.id(), policy.version()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }
}
