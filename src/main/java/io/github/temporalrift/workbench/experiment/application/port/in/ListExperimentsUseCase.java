package io.github.temporalrift.workbench.experiment.application.port.in;

import java.time.Instant;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.Page;

/** Lists frozen experiments, newest first. */
public interface ListExperimentsUseCase {

    Page<Summary> handle(int limit, int offset);

    record Summary(UUID experimentId, String name, String manifestDigest, Instant createdAt) {}
}
