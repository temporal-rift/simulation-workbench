package io.github.temporalrift.workbench.experiment.application.query;

import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.experiment.LaneBundleCatalog;
import io.github.temporalrift.workbench.experiment.application.port.in.ListLaneBundlesUseCase;
import io.github.temporalrift.workbench.shared.Page;

public class ListLaneBundlesQueryHandler implements ListLaneBundlesUseCase {

    private final LaneBundleCatalog bundles;

    public ListLaneBundlesQueryHandler(LaneBundleCatalog bundles) {
        this.bundles = bundles;
    }

    @Override
    public Page<LaneBundle> handle(int limit, int offset) {
        return Page.of(bundles.bundles(), limit, offset);
    }
}
