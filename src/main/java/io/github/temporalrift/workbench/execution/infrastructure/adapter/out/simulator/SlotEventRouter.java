package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.github.temporalrift.workbench.execution.LaneService;

/**
 * Carries the records the services of one slot publish to the services that consume them, as the broker would for one
 * game: a record reaches a consuming service once, after the one before it on the same topic returned from that
 * service, and a record is never offered to the service that published its topic. Every record is also retained, so
 * an observer reads what was routed from any offset.
 */
final class SlotEventRouter implements AutoCloseable {

    static final String GAME_EVENTS = "game.events";
    static final String TIMELINE_EVENTS = "timeline.events";

    static final String GAME_SERVICE = LaneService.GAME.serviceName();
    static final String TIMELINE_SERVICE = LaneService.TIMELINE.serviceName();
    static final String READ_SERVICE = LaneService.READ.serviceName();

    /** The services that consume each topic. */
    private static final Map<String, List<String>> CONSUMERS = Map.of(
            GAME_EVENTS, List.of(TIMELINE_SERVICE, READ_SERVICE),
            TIMELINE_EVENTS, List.of(GAME_SERVICE, READ_SERVICE));

    /** One record as a service published it, at its position on its topic. */
    record Routed(String topic, long offset, String key, Map<String, String> headers, byte[] payload) {

        Map<String, Object> asDelivered() {
            return Map.of("topic", topic, "key", key, "headers", headers, "payload", payload);
        }

        @Override
        public boolean equals(Object other) {
            return other
                            instanceof
                            Routed(
                                    String otherTopic,
                                    long otherOffset,
                                    String otherKey,
                                    Map<String, String> otherHeaders,
                                    byte[] otherPayload)
                    && offset == otherOffset
                    && topic.equals(otherTopic)
                    && key.equals(otherKey)
                    && headers.equals(otherHeaders)
                    && Arrays.equals(payload, otherPayload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(topic, offset, key, headers) * 31 + Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "Routed[" + topic + "@" + offset + ", key=" + key + ", " + payload.length + " bytes]";
        }
    }

    private final Map<String, List<Routed>> log = new HashMap<>();
    private final Map<String, List<Delivery>> deliveries = new HashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private boolean bound;

    /** One consuming service's queue of one topic: a single thread, so records arrive in publication order. */
    private final class Delivery {
        private final String service;
        private final Consumer<Map<String, Object>> consumer;
        private final ExecutorService queue;

        private Delivery(String topic, String service, Consumer<Map<String, Object>> consumer) {
            this.service = service;
            this.consumer = consumer;
            this.queue = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                    .name("router-" + topic + "-" + service)
                    .daemon()
                    .factory());
        }

        private void offer(Routed item) {
            pending.incrementAndGet();
            queue.execute(() -> {
                try {
                    consumer.accept(item.asDelivered());
                } catch (RuntimeException e) {
                    failure.compareAndSet(
                            null,
                            new IllegalStateException(
                                    service + " failed to handle a record of " + item.topic() + " at offset "
                                            + item.offset() + ": " + e.getMessage(),
                                    e));
                } finally {
                    pending.decrementAndGet();
                }
            });
        }
    }

    /** The sink a publishing service hands each record to. */
    @SuppressWarnings("unchecked")
    void publish(Map<String, Object> item) {
        var topic = (String) item.get("topic");
        if (!CONSUMERS.containsKey(topic)) {
            throw new IllegalArgumentException("A record was published on the unknown topic " + topic);
        }
        synchronized (this) {
            var onTopic = log.computeIfAbsent(topic, _ -> new ArrayList<>());
            var routed = new Routed(
                    topic,
                    onTopic.size(),
                    (String) item.get("key"),
                    Map.copyOf((Map<String, String>) item.get("headers")),
                    ((byte[]) item.get("payload")).clone());
            onTopic.add(routed);
            if (bound) {
                deliveries.get(topic).forEach(delivery -> delivery.offer(routed));
            }
        }
    }

    /**
     * Starts delivering to the services' entries. Records published while the services were still starting are
     * delivered first, in publication order.
     */
    synchronized void bind(Map<String, Consumer<Map<String, Object>>> entries) {
        CONSUMERS.forEach((topic, services) -> deliveries.put(
                topic,
                services.stream()
                        .map(service -> new Delivery(topic, service, entries.get(service)))
                        .toList()));
        bound = true;
        log.forEach((topic, records) ->
                records.forEach(item -> deliveries.get(topic).forEach(delivery -> delivery.offer(item))));
    }

    /** Whether every routed record has been delivered to every consuming service. */
    boolean idle() {
        return pending.get() == 0;
    }

    /** The first delivery a service failed, which leaves the slot's services out of step; null when none failed. */
    RuntimeException failure() {
        return failure.get();
    }

    /** The records routed on {@code topic} from {@code offset} on. */
    synchronized List<Routed> routed(String topic, long offset) {
        var onTopic = log.getOrDefault(topic, List.of());
        return List.copyOf(onTopic.subList((int) Math.min(offset, onTopic.size()), onTopic.size()));
    }

    @Override
    public synchronized void close() {
        deliveries.values().forEach(onTopic -> onTopic.forEach(delivery -> delivery.queue.shutdownNow()));
    }
}
