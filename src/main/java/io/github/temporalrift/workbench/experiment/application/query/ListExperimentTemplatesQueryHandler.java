package io.github.temporalrift.workbench.experiment.application.query;

import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.experiment.LaneBundleCatalog;
import io.github.temporalrift.workbench.experiment.application.port.in.ListExperimentTemplatesUseCase;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyArtifacts;
import io.github.temporalrift.workbench.experiment.domain.template.ExperimentTemplate;
import io.github.temporalrift.workbench.experiment.domain.template.ExperimentTemplates;
import io.github.temporalrift.workbench.experiment.domain.template.TemplateBounds;
import io.github.temporalrift.workbench.shared.Page;

/** Builds the templates on every read, so their concurrency follows the lanes configured now. */
public class ListExperimentTemplatesQueryHandler implements ListExperimentTemplatesUseCase {

    private final LaneBundleCatalog bundles;
    private final PolicyArtifacts policies;
    private final TemplateBounds bounds;

    public ListExperimentTemplatesQueryHandler(
            LaneBundleCatalog bundles, PolicyArtifacts policies, TemplateBounds bounds) {
        this.bundles = bundles;
        this.policies = policies;
        this.bounds = bounds;
    }

    @Override
    public Page<ExperimentTemplate> handle(int limit, int offset) {
        int lanes = bundles.bundles().stream().mapToInt(LaneBundle::laneCount).sum();
        return Page.of(ExperimentTemplates.builtIn(lanes, bounds, policies), limit, offset);
    }
}
