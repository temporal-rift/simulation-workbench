package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.GameEventObservers;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;

/**
 * Opens a Kafka observer per game on the lane event topics, with the workbench Kafka client settings and the broker
 * of the lane, or the workbench default broker for a lane that names none.
 */
public class KafkaGameEventObservers implements GameEventObservers {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(250);
    private static final int MAX_IDLE_POLLS = 8;

    private final Supplier<Map<String, Object>> clientProperties;
    private final EvidenceLedger evidence;
    private final ObjectMapper objectMapper;

    public KafkaGameEventObservers(
            Supplier<Map<String, Object>> clientProperties, EvidenceLedger evidence, ObjectMapper objectMapper) {
        this.clientProperties = clientProperties;
        this.evidence = evidence;
        this.objectMapper = objectMapper;
    }

    @Override
    public GameEventObserver open(LaneEndpoints lane, UUID scopeId, UUID attemptId, UUID gameId, Instant since) {
        var properties = new HashMap<>(clientProperties.get());
        if (lane.kafkaBootstrapServers() != null) {
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, lane.kafkaBootstrapServers());
        }
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "workbench-observer-" + attemptId);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "workbench-observer-" + gameId);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var consumer = new KafkaConsumer<String, byte[]>(properties);
        Admin admin = null;
        try {
            admin = Admin.create(adminProperties(properties));
            return new KafkaGameEventObserver(
                    consumer,
                    List.of(lane.gameEventsTopic(), lane.timelineEventsTopic()),
                    new KafkaGameEventObserver.Collaborators(evidence, objectMapper, new KafkaReadSideOffsets(admin)),
                    new KafkaGameEventObserver.Target(scopeId, attemptId, gameId),
                    new KafkaGameEventObserver.Polling(POLL_TIMEOUT, MAX_IDLE_POLLS),
                    since);
        } catch (RuntimeException e) {
            consumer.close();
            if (admin != null) {
                admin.close();
            }
            throw e;
        }
    }

    /** The broker address and security settings of the consumer, without its consumer-only settings. */
    private static Map<String, Object> adminProperties(Map<String, Object> consumer) {
        var admin = new HashMap<String, Object>();
        consumer.forEach((key, value) -> {
            if (key.equals(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG)
                    || key.startsWith("security.")
                    || key.startsWith("sasl.")
                    || key.startsWith("ssl.")) {
                admin.put(key, value);
            }
        });
        return admin;
    }
}
