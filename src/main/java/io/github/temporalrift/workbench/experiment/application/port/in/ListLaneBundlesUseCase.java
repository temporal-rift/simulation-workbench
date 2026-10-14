package io.github.temporalrift.workbench.experiment.application.port.in;

import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.shared.Page;

/** Lists the bundles a variant may select, one per distinct effective digests the configured lanes serve. */
public interface ListLaneBundlesUseCase {

    Page<LaneBundle> handle(int limit, int offset);
}
