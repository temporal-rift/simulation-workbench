package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;

class SimulatorEventObserverTest {

    private static final UUID SCOPE = new UUID(1, 1);
    private static final UUID ATTEMPT = new UUID(2, 2);
    private static final UUID GAME = new UUID(3, 3);
    private static final UUID OTHER_GAME = new UUID(4, 4);
    private static final Consumer<Map<String, Object>> NOBODY = _ -> {};

    private final SlotEventRouter router = new SlotEventRouter();
    private final EvidenceLedger evidence = new InMemoryEvidenceLedger();

    @AfterEach
    void closeRouter() {
        router.close();
    }

    @Test
    void retainsEveryRecordRoutedForItsGameAsDeliveredAndNothingOfAnotherGame() {
        router.bind(allTo(NOBODY));
        var first = publish(SlotEventRouter.GAME_EVENTS, GAME, "LobbyStarted", "{\"n\":1}");
        publish(SlotEventRouter.GAME_EVENTS, OTHER_GAME, "LobbyStarted", "{\"n\":2}");
        var second = publish(SlotEventRouter.TIMELINE_EVENTS, GAME, "BandsPublished", "{\"n\":3}");
        awaitIdle();
        var observer = observer(GAME);

        assertThat(observer.drain()).isTrue();

        assertThat(evidence.events(SCOPE, GAME))
                .extracting(ObservedEvent::eventId, ObservedEvent::source, ObservedEvent::offset)
                .containsExactlyInAnyOrder(
                        tuple(first, SlotEventRouter.GAME_EVENTS, 0L),
                        tuple(second, SlotEventRouter.TIMELINE_EVENTS, 0L));
        assertThat(evidence.events(SCOPE, GAME))
                .extracting(ObservedEvent::payload)
                .containsExactlyInAnyOrder("{\"n\":1}", "{\"n\":3}");
        assertThat(evidence.events(SCOPE, OTHER_GAME)).isEmpty();
    }

    @Test
    void doesNotReportTheEndOfTheSourcesWhileADeliveryIsStillOnItsWay() throws Exception {
        var release = new CountDownLatch(1);
        Consumer<Map<String, Object>> blocked = _ -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        router.bind(allTo(blocked));
        publish(SlotEventRouter.GAME_EVENTS, GAME, "LobbyStarted", "{}");
        var observer = observer(GAME);

        assertThat(observer.drain()).isFalse();

        release.countDown();
        awaitIdle();
        assertThat(observer.drain()).isTrue();
        assertThat(evidence.events(SCOPE, GAME)).hasSize(1);
    }

    @Test
    void resumesFromTheOffsetsItRetainedWithoutRetainingARecordTwice() {
        router.bind(allTo(NOBODY));
        publish(SlotEventRouter.GAME_EVENTS, GAME, "LobbyStarted", "{}");
        awaitIdle();
        observer(GAME).drain();
        publish(SlotEventRouter.GAME_EVENTS, GAME, "EraStarted", "{}");
        awaitIdle();

        observer(GAME).drain();

        assertThat(evidence.events(SCOPE, GAME))
                .extracting(ObservedEvent::eventType)
                .containsExactlyInAnyOrder("LobbyStarted", "EraStarted");
        assertThat(evidence.offsets(SCOPE, GAME))
                .containsEntry(new EvidenceLedger.SourcePartition(SlotEventRouter.GAME_EVENTS, 0), 2L);
    }

    @Test
    void reportsTheTerminalFactOnceGameEndedWasRouted() {
        router.bind(allTo(NOBODY));
        var winner = new UUID(9, 9);
        publish(
                SlotEventRouter.GAME_EVENTS,
                GAME,
                "GameEnded",
                "{\"gameId\":\"" + GAME + "\",\"endReason\":\"PARADOX_COLLAPSE\"," + "\"finalScores\":[{\"playerId\":\""
                        + winner + "\",\"faction\":\"WEAVERS\",\"score\":7}]}");
        awaitIdle();
        var observer = observer(GAME);
        assertThat(observer.gameEnded()).isEmpty();

        observer.drain();

        assertThat(observer.gameEnded()).hasValueSatisfying(ended -> {
            assertThat(ended.endReason()).isEqualTo("PARADOX_COLLAPSE");
            assertThat(ended.finalScores()).isEqualTo(Map.of(winner, 7));
        });
    }

    @Test
    void aDeliveryAServiceFailedMakesTheDrainFailTheAttempt() {
        Consumer<Map<String, Object>> failing = _ -> {
            throw new IllegalStateException("handler failed");
        };
        router.bind(allTo(failing));
        publish(SlotEventRouter.GAME_EVENTS, GAME, "LobbyStarted", "{}");
        awaitIdle();

        assertThatThrownBy(() -> observer(GAME).drain())
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().code()).isEqualTo(FailureCode.EXECUTION_FAILED))
                .hasMessageContaining("handler failed");
    }

    private SimulatorEventObserver observer(UUID game) {
        return new SimulatorEventObserver(
                router,
                evidence,
                JsonMapper.builder().build(),
                new SimulatorEventObserver.Target(SCOPE, ATTEMPT, game));
    }

    private UUID publish(String topic, UUID game, String type, String payload) {
        var eventId = UUID.randomUUID();
        router.publish(Map.of(
                "topic",
                topic,
                "key",
                game.toString(),
                "headers",
                Map.of(
                        "eventType", type,
                        "eventId", eventId.toString(),
                        "aggregateId", game.toString(),
                        "aggregateType", "Game",
                        "gameId", game.toString(),
                        "occurredAt", Instant.parse("2026-01-01T00:00:00Z").toString(),
                        "version", "1"),
                "payload",
                payload.getBytes(StandardCharsets.UTF_8)));
        return eventId;
    }

    private static Map<String, Consumer<Map<String, Object>>> allTo(Consumer<Map<String, Object>> consumer) {
        return Map.of(
                SlotEventRouter.GAME_SERVICE, consumer,
                SlotEventRouter.TIMELINE_SERVICE, consumer,
                SlotEventRouter.READ_SERVICE, consumer);
    }

    private void awaitIdle() {
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(router::idle);
    }
}
