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
        consumer.schedulePollTask(() -> consumer.addRecord(record(0, true)));
        consumer.schedulePollTask(() -> consumer.addRecord(record(1, false)));
        var evidence = new InMemoryEvidenceLedger();
        var observer = new KafkaGameEventObserver(
                consumer,
                List.of(TOPIC),
                evidence,
                JsonMapper.builder().build(),
                new KafkaGameEventObserver.Target(SCOPE, UUID.randomUUID(), GAME),
                new KafkaGameEventObserver.Polling(Duration.ofMillis(1), 3),
                null);

        assertThatThrownBy(observer::drain).isInstanceOf(AttemptFailedException.class);

        assertThat(evidence.events(SCOPE, GAME)).hasSize(1);
        assertThat(evidence.offsets(SCOPE, GAME)).isEmpty();
    }

    private static ConsumerRecord<String, byte[]> record(long offset, boolean identified) {
        var record = new ConsumerRecord<>(TOPIC, 0, offset, GAME.toString(), "{}".getBytes(StandardCharsets.UTF_8));
        record.headers().add("gameId", GAME.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", "ScoresUpdated".getBytes(StandardCharsets.UTF_8));
        if (identified) {
            record.headers().add("eventId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
