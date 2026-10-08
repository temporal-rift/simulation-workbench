package io.github.temporalrift.workbench.execution.domain.evidence;

import java.util.Objects;

/**
 * The artifacts a case is reproduced from, each verified against its content address when read:
 * the exact manifest and the accepted decision transcript, with the semantic digest the case produced.
 */
public record PinnedEvidence(String manifestDigest, String manifestJson, String transcript, String resultDigest) {

    public PinnedEvidence {
        Objects.requireNonNull(manifestDigest, "manifestDigest");
        Objects.requireNonNull(manifestJson, "manifestJson");
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(resultDigest, "resultDigest");
    }
}
