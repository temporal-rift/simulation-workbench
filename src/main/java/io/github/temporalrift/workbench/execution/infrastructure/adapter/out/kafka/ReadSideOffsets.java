package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.kafka;

import java.util.Map;

import org.apache.kafka.common.TopicPartition;

/** What the read-side consumer groups have committed, which read-service exposes nowhere else. */
@FunctionalInterface
public interface ReadSideOffsets extends AutoCloseable {

    /** Whether every read-side group has committed at or past {@code ends} on the partitions it has committed on. */
    boolean reached(Map<TopicPartition, Long> ends);

    @Override
    default void close() {}
}
