package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;

class KafkaGameEventObserverDrainTest {

    private static final String TOPIC = "game.events";
    private static final UUID SCOPE = new UUID(1, 1);
    private static final UUID GAME = new UUID(2, 2);

    @Test
    void aDrainThatFailsAfterAnEarlierPollWasRetainedAdvancesNoOffset() {
        var partition = new TopicPartition(TOPIC, 0);
        var consumer = new MockConsumer<String, byte[]>(AutoOffsetResetStrategy.EARLIEST.name());
        consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        consumer.updateBeginningOffsets(java.util.Map.of(partition, 0L));
        consumer.updateEndOffsets(java.util.Map.of(partition, 2L));
        consumer.schedulePollTask(() -> consumer.addRecord(message(0, true)));
        consumer.schedulePollTask(() -> consumer.addRecord(message(1, false)));
        var evidence = new InMemoryEvidenceLedger();
        var observer = new KafkaGameEventObserver(
                consumer,
                List.of(TOPIC),
                evidence,
                JsonMapper.builder().build(),
                new KafkaGameEventObserver.Target(SCOPE, UUID.randomUUID(), GAME),
                new KafkaGameEventObserver.Polling(Duration.ofMillis(1), 3),
                _ -> true,
                null);

        assertThatThrownBy(observer::drain).isInstanceOf(AttemptFailedException.class);

        assertThat(evidence.events(SCOPE, GAME)).hasSize(1);
        assertThat(evidence.offsets(SCOPE, GAME)).isEmpty();
    }

    @Test
    void aDrainReportsTheEndOffsetsItReachedAndAsksTheReadSideAboutThem() {
        var partition = new TopicPartition(TOPIC, 0);
        var consumer = new MockConsumer<String, byte[]>(AutoOffsetResetStrategy.EARLIEST.name());
        consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        consumer.updateBeginningOffsets(java.util.Map.of(partition, 0L));
        consumer.updateEndOffsets(java.util.Map.of(partition, 2L));
        consumer.schedulePollTask(() -> {
            consumer.addRecord(message(0, true));
            consumer.addRecord(message(1, true));
        });
        var asked = new java.util.concurrent.atomic.AtomicReference<java.util.Map<TopicPartition, Long>>();
        var observer = new KafkaGameEventObserver(
                consumer,
                List.of(TOPIC),
                new InMemoryEvidenceLedger(),
                JsonMapper.builder().build(),
                new KafkaGameEventObserver.Target(SCOPE, UUID.randomUUID(), GAME),
                new KafkaGameEventObserver.Polling(Duration.ofMillis(1), 3),
                ends -> {
                    asked.set(ends);
                    return false;
                },
                null);

        assertThat(observer.drain()).isTrue();

        assertThat(observer.endOffsets())
                .containsExactly(java.util.Map.entry(new EvidenceLedger.SourcePartition(TOPIC, 0), 2L));
        assertThat(observer.readSideApplied()).isFalse();
        assertThat(asked.get()).containsExactly(java.util.Map.entry(partition, 2L));
    }

    private static ConsumerRecord<String, byte[]> message(long offset, boolean identified) {
        var message = new ConsumerRecord<>(TOPIC, 0, offset, GAME.toString(), "{}".getBytes(StandardCharsets.UTF_8));
        message.headers().add("gameId", GAME.toString().getBytes(StandardCharsets.UTF_8));
        message.headers().add("eventType", "ScoresUpdated".getBytes(StandardCharsets.UTF_8));
        if (identified) {
            message.headers().add("eventId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        }
        return message;
    }
}
