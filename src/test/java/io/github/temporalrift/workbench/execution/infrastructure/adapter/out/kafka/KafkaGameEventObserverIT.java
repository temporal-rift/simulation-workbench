package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.GameEventObservers;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;

@WorkbenchIntegrationTest
class KafkaGameEventObserverIT {

    private static final UUID SCOPE = new UUID(1, 1);
    private static final UUID OTHER_SCOPE = new UUID(1, 2);
    private static final UUID GAME = new UUID(2, 2);
    private static final UUID OTHER_GAME = new UUID(2, 3);
    private static final UUID PLAYER = new UUID(4, 4);

    @Autowired
    private KafkaProperties kafka;

    @Autowired
    private KafkaConnectionDetails connection;

    @Autowired
    private GameEventObservers observers;

    @Autowired
    private EvidenceLedger evidence;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private static final KafkaContainer OTHER_BROKER = new KafkaContainer("apache/kafka:3.7.0");

    private String gameTopic;
    private String timelineTopic;

    @BeforeEach
    void setUp() {
        clean();
        var suffix = UUID.randomUUID().toString();
        gameTopic = "game.events." + suffix;
        timelineTopic = "timeline.events." + suffix;
    }

    @AfterAll
    static void stopOtherBroker() {
        OTHER_BROKER.stop();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM evidence_event WHERE scope_id IN (?, ?)", SCOPE, OTHER_SCOPE);
        jdbc.update("DELETE FROM evidence_source_offset WHERE scope_id IN (?, ?)", SCOPE, OTHER_SCOPE);
    }

    @Test
    void itRetainsOnlyThisGamesEventsOnceEachWithTheirSourceOffsetsAndReadsTheEnding() {
        var started = UUID.randomUUID();
        var resolved = UUID.randomUUID();
        var ended = UUID.randomUUID();
        publish(
                gameTopic,
                message(GAME, started, "GameStarted", "{\"gameId\":\"" + GAME + "\"}"),
                message(GAME, started, "GameStarted", "{\"gameId\":\"" + GAME + "\"}"),
                message(OTHER_GAME, UUID.randomUUID(), "GameStarted", "{}"),
                message(GAME, ended, "GameEnded", gameEnded("WIN_CONDITION_MET", 30)),
                message(GAME, started, "GameStarted", "{\"gameId\":\"" + GAME + "\"}"));
        publish(timelineTopic, message(GAME, resolved, "EraResolutionCompleted", "{}"));

        try (var observer = observers.open(lane(), SCOPE, UUID.randomUUID(), GAME, null)) {
            assertThat(observer.drain()).isTrue();

            assertThat(evidence.events(SCOPE, GAME))
                    .extracting(event ->
                            (event.source().startsWith("game.") ? "game" : "timeline") + ":" + event.eventType())
                    .containsExactlyInAnyOrder("game:GameStarted", "game:GameEnded", "timeline:EraResolutionCompleted");
            assertThat(observer.gameEnded()).hasValueSatisfying(fact -> {
                assertThat(fact.endReason()).isEqualTo("WIN_CONDITION_MET");
                assertThat(fact.finalScores()).containsEntry(PLAYER, 30);
            });
        }

        assertThat(evidence.offsets(SCOPE, GAME))
                .containsEntry(new EvidenceLedger.SourcePartition(gameTopic, 0), 5L)
                .containsEntry(new EvidenceLedger.SourcePartition(timelineTopic, 0), 1L);
        var first = evidence.events(SCOPE, GAME).stream()
                .filter(event -> event.eventType().equals("GameStarted"))
                .findFirst()
                .orElseThrow();
        assertThat(first.offset()).isZero();
        assertThat(first.eventId()).isEqualTo(started);
        assertThat(first.aggregateType()).isEqualTo("Game");
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.occurredAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void anObserverThatAttachesAgainRetainsNothingTwice() {
        publish(gameTopic, message(GAME, UUID.randomUUID(), "GameStarted", "{}"));
        try (var first = observers.open(lane(), SCOPE, UUID.randomUUID(), GAME, null)) {
            first.drain();
        }

        try (var second = observers.open(lane(), SCOPE, UUID.randomUUID(), GAME, null)) {
            assertThat(second.drain()).isTrue();
        }

        assertThat(evidence.events(SCOPE, GAME)).hasSize(1);
    }

    @Test
    void aFreshGameDoesNotReadTheHistoryOfEarlierGamesOnTheLane() {
        var began = Instant.parse("2026-01-01T00:00:00Z");
        publish(gameTopic, message(GAME, UUID.randomUUID(), "EarlierEvent", "{}", began.minusSeconds(60)));
        publish(gameTopic, message(GAME, UUID.randomUUID(), "GameStarted", "{}", began.plusSeconds(60)));

        try (var observer = observers.open(lane(), SCOPE, UUID.randomUUID(), GAME, began)) {
            assertThat(observer.drain()).isTrue();
        }

        assertThat(evidence.events(SCOPE, GAME))
                .extracting(ObservedEvent::eventType)
                .containsExactly("GameStarted");
        assertThat(evidence.offsets(SCOPE, GAME)).containsEntry(new EvidenceLedger.SourcePartition(gameTopic, 0), 2L);
    }

    @Test
    void anObserverThatAttachesAgainResumesFromTheRetainedOffsetsAndStillKnowsTheEnding() {
        publish(gameTopic, message(GAME, UUID.randomUUID(), "GameEnded", gameEnded("WIN_CONDITION_MET", 30)));
        try (var first = observers.open(lane(), SCOPE, UUID.randomUUID(), GAME, null)) {
            first.drain();
        }
        publish(gameTopic, message(GAME, UUID.randomUUID(), "ScoresUpdated", "{}"));

        try (var second = observers.open(lane(), SCOPE, UUID.randomUUID(), GAME, null)) {
            assertThat(second.gameEnded()).isPresent();
            assertThat(second.drain()).isTrue();
        }

        assertThat(evidence.events(SCOPE, GAME))
                .extracting(ObservedEvent::eventType)
                .containsExactlyInAnyOrder("GameEnded", "ScoresUpdated");
        assertThat(evidence.offsets(SCOPE, GAME)).containsEntry(new EvidenceLedger.SourcePartition(gameTopic, 0), 2L);
    }

    @Test
    void eventsPublishedLaterAreRetainedByTheNextDrain() {
        publish(gameTopic, message(GAME, UUID.randomUUID(), "GameStarted", "{}"));
        try (var observer = observers.open(lane(), SCOPE, UUID.randomUUID(), GAME, null)) {
            observer.drain();
            assertThat(observer.gameEnded()).isEmpty();

            publish(gameTopic, message(GAME, UUID.randomUUID(), "GameEnded", gameEnded("ALL_PLAYERS_ABANDONED", 5)));

            assertThat(observer.drain()).isTrue();
            assertThat(observer.gameEnded())
                    .hasValueSatisfying(fact -> assertThat(fact.endReason()).isEqualTo("ALL_PLAYERS_ABANDONED"));
        }
        assertThat(evidence.events(SCOPE, GAME)).hasSize(2);
    }

    @Test
    void aRecordOfThisGameWithoutAnEventIdentifierIsAContractMismatch() {
        publish(
                gameTopic,
                new ProducerRecord<>(gameTopic, null, GAME.toString(), "{}".getBytes(StandardCharsets.UTF_8)));

        try (var observer = observers.open(lane(), SCOPE, UUID.randomUUID(), GAME, null)) {
            assertThatThrownBy(observer::drain)
                    .isInstanceOfSatisfying(
                            AttemptFailedException.class,
                            e -> assertThat(e.failure().code()).isEqualTo(FailureCode.CONTRACT_MISMATCH));
        }
    }

    @Test
    void aLaneWithItsOwnBrokerIsObservedThroughThatBrokerAlone() {
        if (!OTHER_BROKER.isRunning()) {
            OTHER_BROKER.start();
        }
        var onDefault = UUID.randomUUID();
        var onOther = UUID.randomUUID();
        var shared = UUID.randomUUID();
        publish(
                gameTopic,
                message(GAME, shared, "GameStarted", "{}"),
                message(GAME, onDefault, "GameEnded", gameEnded("ALL_PLAYERS_ABANDONED", 3)));
        publish(
                OTHER_BROKER.getBootstrapServers(),
                gameTopic,
                message(GAME, shared, "GameStarted", "{\"lane\":\"other\"}"),
                message(GAME, onOther, "GameEnded", gameEnded("WIN_CONDITION_MET", 7)));
        var otherLane = new LaneEndpoints(
                "other",
                "http://g",
                "http://t",
                "http://r",
                "op",
                new BundleDigests("a".repeat(64), "b".repeat(64)),
                gameTopic,
                timelineTopic,
                OTHER_BROKER.getBootstrapServers(),
                null,
                List.of());

        try (var defaultObserver = observers.open(lane(), SCOPE, UUID.randomUUID(), GAME, null);
                var otherObserver = observers.open(otherLane, OTHER_SCOPE, UUID.randomUUID(), GAME, null)) {
            assertThat(defaultObserver.drain()).isTrue();
            assertThat(otherObserver.drain()).isTrue();
        }

        assertThat(evidence.events(SCOPE, GAME))
                .extracting(ObservedEvent::eventId)
                .containsExactlyInAnyOrder(shared, onDefault);
        assertThat(evidence.events(OTHER_SCOPE, GAME))
                .extracting(ObservedEvent::eventId)
                .containsExactlyInAnyOrder(shared, onOther);
        assertThat(evidence.events(OTHER_SCOPE, GAME))
                .filteredOn(event -> event.eventId().equals(shared))
                .singleElement()
                .satisfies(event -> assertThat(event.payload()).contains("other"));
    }

    private LaneEndpoints lane() {
        return new LaneEndpoints(
                "lane",
                "http://g",
                "http://t",
                "http://r",
                "op",
                new BundleDigests("a".repeat(64), "b".repeat(64)),
                gameTopic,
                timelineTopic,
                null,
                null,
                List.of());
    }

    private static String gameEnded(String reason, int score) {
        return "{\"gameId\":\"" + GAME + "\",\"endReason\":\"" + reason + "\",\"finalScores\":[{\"playerId\":\""
                + PLAYER + "\",\"faction\":\"ERASERS\",\"score\":" + score + "}]}";
    }

    private static ProducerRecord<String, byte[]> message(UUID gameId, UUID eventId, String type, String payload) {
        return message(gameId, eventId, type, payload, null);
    }

    /** A record created at the given time, so a test controls where a game began without waiting. */
    private static ProducerRecord<String, byte[]> message(
            UUID gameId, UUID eventId, String type, String payload, Instant createdAt) {
        var producerRecord = new ProducerRecord<String, byte[]>(
                "pending",
                null,
                createdAt == null ? null : createdAt.toEpochMilli(),
                gameId.toString(),
                payload.getBytes(StandardCharsets.UTF_8));
        header(producerRecord, "eventType", type);
        header(producerRecord, "eventId", eventId.toString());
        header(producerRecord, "aggregateId", gameId.toString());
        header(producerRecord, "aggregateType", "Game");
        header(producerRecord, "gameId", gameId.toString());
        header(producerRecord, "occurredAt", "2026-01-01T00:00:00Z");
        header(producerRecord, "version", "1");
        return producerRecord;
    }

    private static void header(ProducerRecord<String, byte[]> message, String name, String value) {
        message.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    @SafeVarargs
    private void publish(String topic, ProducerRecord<String, byte[]>... records) {
        publish(String.join(",", connection.getProducer().getBootstrapServers()), topic, records);
    }

    @SafeVarargs
    private void publish(String bootstrapServers, String topic, ProducerRecord<String, byte[]>... records) {
        Map<String, Object> properties = new java.util.HashMap<>(kafka.buildProducerProperties());
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (var producer = new KafkaProducer<String, byte[]>(properties)) {
            for (var pending : records) {
                var withTopic = new ProducerRecord<>(
                        topic, null, pending.timestamp(), pending.key(), pending.value(), pending.headers());
                producer.send(withTopic);
            }
            producer.flush();
        }
    }
}
