package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Changes to evidence rows that already exist. */
@Component
class EvidenceUpdates {

    private final CaseEvidenceJpaRepository evidence;

    EvidenceUpdates(CaseEvidenceJpaRepository evidence) {
        this.evidence = evidence;
    }

    @Transactional
    void seal(UUID scopeId, String transcriptArtifact, String resultDigest, Instant now) {
        evidence.findById(scopeId)
                .orElseThrow(
                        () -> new IllegalStateException("Evidence of " + scopeId + " was sealed before it was pinned"))
                .seal(transcriptArtifact, resultDigest, now);
    }
}
