package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Changes to evidence rows that already exist, under their row locks. */
@Component
class EvidenceUpdates {

    private final CaseEvidenceJpaRepository evidence;
    private final EvidenceSourceOffsetJpaRepository offsets;

    EvidenceUpdates(CaseEvidenceJpaRepository evidence, EvidenceSourceOffsetJpaRepository offsets) {
        this.evidence = evidence;
        this.offsets = offsets;
    }

    @Transactional
    void seal(UUID scopeId, String transcriptArtifact, String resultDigest, Instant now) {
        evidence.findById(scopeId)
                .orElseThrow(
                        () -> new IllegalStateException("Evidence of " + scopeId + " was sealed before it was pinned"))
                .seal(transcriptArtifact, resultDigest, now);
    }

    /** The offset never moves back, even when two writers race. */
    @Transactional
    void raiseOffset(UUID scopeId, UUID gameId, String source, int partition, long nextOffset) {
        offsets.findWithLockByScopeIdAndGameIdAndSourceAndPartitionNo(scopeId, gameId, source, partition)
                .orElseThrow(() -> new IllegalStateException("Source offset vanished: " + source + "/" + partition))
                .advanceTo(nextOffset);
    }
}
