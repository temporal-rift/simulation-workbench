package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SlotEventRouterTest {

    private final SlotEventRouter router = new SlotEventRouter();
    private final Received game = new Received();
    private final Received timeline = new Received();
    private final Received read = new Received();

    /** The records one service's delivery entry was handed, in order. */
    private static final class Received implements Consumer<Map<String, Object>> {
        private final List<String> payloads = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void accept(Map<String, Object> record) {
            payloads.add(new String((byte[]) record.get("payload"), StandardCharsets.UTF_8));
        }

        List<String> payloads() {
            return List.copyOf(payloads);
        }
    }

    @AfterEach
    void closeRouter() {
        router.close();
    }

    @Test
    void aGameEventsRecordReachesTimelineAndReadOnceAndNeverGame() {
        bindAll();

        router.publish(record(SlotEventRouter.GAME_EVENTS, "a"));
        awaitIdle();

        assertThat(timeline.payloads()).containsExactly("a");
        assertThat(read.payloads()).containsExactly("a");
        assertThat(game.payloads()).isEmpty();
    }

    @Test
    void aTimelineEventsRecordReachesGameAndReadOnceAndNeverTimeline() {
        bindAll();

        router.publish(record(SlotEventRouter.TIMELINE_EVENTS, "t"));
        awaitIdle();

        assertThat(game.payloads()).containsExactly("t");
        assertThat(read.payloads()).containsExactly("t");
        assertThat(timeline.payloads()).isEmpty();
    }

    @Test
    void aRecordIsDeliveredToAServiceOnlyAfterItsPreviousDeliveryReturned() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var order = Collections.synchronizedList(new ArrayList<String>());
        Consumer<Map<String, Object>> slowTimeline = record -> {
            var payload = new String((byte[]) record.get("payload"), StandardCharsets.UTF_8);
            order.add("start " + payload);
            if (payload.equals("first")) {
                firstEntered.countDown();
                await(releaseFirst);
            }
            order.add("end " + payload);
        };
        router.bind(Map.of(
                SlotEventRouter.GAME_SERVICE, game,
                SlotEventRouter.TIMELINE_SERVICE, slowTimeline,
                SlotEventRouter.READ_SERVICE, read));

        router.publish(record(SlotEventRouter.GAME_EVENTS, "first"));
        router.publish(record(SlotEventRouter.GAME_EVENTS, "second"));
        await(firstEntered);

        assertThat(router.idle()).isFalse();
        assertThat(order).containsExactly("start first");

        releaseFirst.countDown();
        awaitIdle();

        assertThat(order).containsExactly("start first", "end first", "start second", "end second");
        assertThat(read.payloads()).containsExactly("first", "second");
    }

    @Test
    void recordsPublishedBeforeTheServicesAreBoundAreDeliveredFirstAndInOrder() {
        router.publish(record(SlotEventRouter.GAME_EVENTS, "early-1"));
        router.publish(record(SlotEventRouter.GAME_EVENTS, "early-2"));

        bindAll();
        router.publish(record(SlotEventRouter.GAME_EVENTS, "late"));
        awaitIdle();

        assertThat(timeline.payloads()).containsExactly("early-1", "early-2", "late");
    }

    @Test
    void everyRoutedRecordIsRetainedAtItsOffsetOnItsTopic() {
        bindAll();

        router.publish(record(SlotEventRouter.GAME_EVENTS, "a"));
        router.publish(record(SlotEventRouter.TIMELINE_EVENTS, "b"));
        router.publish(record(SlotEventRouter.GAME_EVENTS, "c"));
        awaitIdle();

        assertThat(router.routed(SlotEventRouter.GAME_EVENTS, 0))
                .extracting(SlotEventRouter.Routed::offset)
                .containsExactly(0L, 1L);
        assertThat(router.routed(SlotEventRouter.GAME_EVENTS, 1))
                .extracting(routed -> new String(routed.payload(), StandardCharsets.UTF_8))
                .containsExactly("c");
        assertThat(router.routed(SlotEventRouter.TIMELINE_EVENTS, 5)).isEmpty();
    }

    @Test
    void aFailedDeliveryIsReportedAndDoesNotStopLaterOnes() {
        Consumer<Map<String, Object>> failing = record -> {
            throw new IllegalStateException("handler failed");
        };
        router.bind(Map.of(
                SlotEventRouter.GAME_SERVICE, game,
                SlotEventRouter.TIMELINE_SERVICE, failing,
                SlotEventRouter.READ_SERVICE, read));

        router.publish(record(SlotEventRouter.GAME_EVENTS, "a"));
        router.publish(record(SlotEventRouter.GAME_EVENTS, "b"));
        awaitIdle();

        assertThat(router.failure()).hasMessageContaining("timeline-service").hasMessageContaining("handler failed");
        assertThat(read.payloads()).containsExactly("a", "b");
    }

    @Test
    void aRecordOnAnUnknownTopicIsRefused() {
        assertThatThrownBy(() -> router.publish(record("other.events", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other.events");
    }

    private void bindAll() {
        router.bind(Map.of(
                SlotEventRouter.GAME_SERVICE, game,
                SlotEventRouter.TIMELINE_SERVICE, timeline,
                SlotEventRouter.READ_SERVICE, read));
    }

    private void awaitIdle() {
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(router::idle);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> record(String topic, String payload) {
        return Map.of(
                "topic",
                topic,
                "key",
                "game",
                "headers",
                Map.of("eventType", "Any"),
                "payload",
                payload.getBytes(StandardCharsets.UTF_8));
    }
}
