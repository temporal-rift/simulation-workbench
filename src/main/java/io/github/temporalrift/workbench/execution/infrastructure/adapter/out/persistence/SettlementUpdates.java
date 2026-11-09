package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotResolution;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/**
 * Settles command slots and evidence steps in one statement each, without reading the rows first. Every caller
 * already runs in a transaction.
 */
@Component
class SettlementUpdates {

    private static final String UNACKNOWLEDGED = "UNACKNOWLEDGED";

    private static final String SLOT = """
            update CaseCommandJpaEntity c
            set c.status = :status, c.resolutionKind = :kind, c.resolutionCode = :code, c.updatedAt = :now
            where c.caseId = :caseId and c.seatIndex = :seatIndex and c.windowKind = :windowKind
              and c.era = :era and c.round = :round
            """;

    private static final String STEPS = """
            update EvidenceStepJpaEntity s set s.outcome = :outcome, s.outcomeCode = :code
            where s.scopeId = :scopeId and s.gameId = :gameId and s.seatIndex = :seatIndex
              and s.windowKind = :windowKind and s.era = :era and s.outcome = :unacknowledged
            """;

    private final EntityManager entities;

    SettlementUpdates(EntityManager entities) {
        this.entities = entities;
    }

    /** Settles the slot; a slot that does not exist is left alone. */
    void resolveSlot(SlotId slot, SlotResolution resolution, Instant now) {
        var window = slot.window();
        entities.createQuery(SLOT)
                .setParameter("status", resolution.status().name())
                .setParameter("kind", CaseCommandJpaEntity.kindOf(resolution))
                .setParameter("code", CaseCommandJpaEntity.codeOf(resolution))
                .setParameter("now", now)
                .setParameter("caseId", slot.caseId())
                .setParameter("seatIndex", slot.seatIndex())
                .setParameter("windowKind", window.kind())
                .setParameter("era", window.era())
                .setParameter("round", CaseCommandJpaEntity.roundColumn(window))
                .executeUpdate();
    }

    /** Settles the unacknowledged steps of a seat in a window, with or without a round. */
    void resolveSteps(UUID scopeId, UUID gameId, int seatIndex, WindowRef window, StepOutcome outcome, String code) {
        var query = entities.createQuery(
                        window.round() == null ? STEPS + " and s.round is null" : STEPS + " and s.round = :round")
                .setParameter("outcome", outcome.name())
                .setParameter("code", code)
                .setParameter("scopeId", scopeId)
                .setParameter("gameId", gameId)
                .setParameter("seatIndex", seatIndex)
                .setParameter("windowKind", window.kind())
                .setParameter("era", window.era())
                .setParameter("unacknowledged", UNACKNOWLEDGED);
        if (window.round() != null) {
            query.setParameter("round", window.round());
        }
        query.executeUpdate();
    }
}
