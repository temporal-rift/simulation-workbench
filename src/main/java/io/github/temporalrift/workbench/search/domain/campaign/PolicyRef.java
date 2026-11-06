package io.github.temporalrift.workbench.search.domain.campaign;

/**
 * A bot policy the balance criterion names.
 *
 * @param majorVersion the major version, as in {@code 1} for {@code 1.2.0}
 */
public record PolicyRef(String id, String majorVersion) {}
