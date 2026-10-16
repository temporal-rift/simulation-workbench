package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.HashMap;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.GameEndedPayload;
import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver.GameEnded;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;

/** Reads the terminal fact out of an observed {@code GameEnded} event, whatever the source that delivered it. */
public final class GameEndedParser {

    public static final String EVENT_TYPE = "GameEnded";

    private GameEndedParser() {}

    public static GameEnded parse(ObjectMapper objectMapper, ObservedEvent event) {
        try {
            var payload = objectMapper.readValue(event.payload(), GameEndedPayload.class);
            var scores = new HashMap<UUID, Integer>();
            payload.finalScores().forEach(score -> scores.put(score.playerId(), score.score()));
            return new GameEnded(payload.endReason(), scores);
        } catch (JacksonException e) {
            throw new AttemptFailedException(
                    FailureCode.CONTRACT_MISMATCH, "GameEnded " + event.eventId() + " is not a valid payload", e);
        }
    }
}
