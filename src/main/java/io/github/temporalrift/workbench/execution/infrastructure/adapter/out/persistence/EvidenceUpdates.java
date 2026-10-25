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

    /** Records the digests the services reported; a scope without pinned evidence, a reproduction, has no row. */
    @Transactional
    void reportRulesDigests(UUID scopeId, String gameRulesDigest, String timelineRulesDigest) {
        evidence.findById(scopeId).ifPresent(row -> row.reportRules(gameRulesDigest, timelineRulesDigest));
    }

    @Transactional
    void seal(UUID scopeId, String transcriptArtifact, String resultDigest, Instant now) {
        evidence.findById(scopeId)
                .orElseThrow(
                        () -> new IllegalStateException("Evidence of " + scopeId + " was sealed before it was pinned"))
                .seal(transcriptArtifact, resultDigest, now);
    }
}
