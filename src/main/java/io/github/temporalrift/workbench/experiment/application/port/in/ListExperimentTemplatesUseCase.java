package io.github.temporalrift.workbench.experiment.application.port.in;

import io.github.temporalrift.workbench.experiment.domain.template.ExperimentTemplate;
import io.github.temporalrift.workbench.shared.Page;

/** Lists the named starting points of an experiment, in template id order. */
public interface ListExperimentTemplatesUseCase {

    Page<ExperimentTemplate> handle(int limit, int offset);
}
