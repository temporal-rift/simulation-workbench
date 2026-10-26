package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.kafka;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.common.TopicPartition;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.LeaseLostException;
import io.github.temporalrift.workbench.shared.execution.FailureCode;

/** Reads the committed offsets of read-service's consumer groups from the broker. */
final class KafkaReadSideOffsets implements ReadSideOffsets {

    private static final String GROUP_PREFIX = LaneService.READ.serviceName() + ".";
    private static final Duration BROKER_TIMEOUT = Duration.ofSeconds(10);

    private final Admin admin;

    KafkaReadSideOffsets(Admin admin) {
        this.admin = admin;
    }

    @Override
    public boolean reached(Map<TopicPartition, Long> ends) {
        try {
            var groups = admin.listGroups().all().get(BROKER_TIMEOUT.toSeconds(), TimeUnit.SECONDS).stream()
                    .map(GroupListing::groupId)
                    .filter(groupId -> groupId.startsWith(GROUP_PREFIX))
                    .toList();
            for (var group : groups) {
                var committed = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata(group)
                        .get(BROKER_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                var behind = committed.entrySet().stream()
                        .anyMatch(entry -> entry.getValue().offset() < ends.getOrDefault(entry.getKey(), 0L));
                if (behind) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LeaseLostException();
        } catch (ExecutionException | TimeoutException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    "The read-service consumer group offsets could not be read: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public void close() {
        admin.close(BROKER_TIMEOUT);
    }
}
