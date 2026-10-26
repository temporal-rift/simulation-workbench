package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.common.TopicPartition;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.LeaseLostException;
import io.github.temporalrift.workbench.shared.execution.FailureCode;

/**
 * Reads read-service's consumer groups from the broker: each group's assigned partitions and what it committed on
 * them. A partition with no commit counts as offset zero, so a group that has not yet handled a record holds back.
 */
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
            var groups = groups();
            if (groups.isEmpty()) {
                return false;
            }
            for (var group : admin.describeConsumerGroups(groups)
                    .all()
                    .get(BROKER_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                    .values()) {
                if (!applied(group, ends)) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new LeaseLostException();
        } catch (ExecutionException | TimeoutException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    "The read-service consumer group offsets could not be read: " + e.getMessage(),
                    e);
        }
    }

    private List<String> groups() throws InterruptedException, ExecutionException, TimeoutException {
        return admin.listGroups().all().get(BROKER_TIMEOUT.toSeconds(), TimeUnit.SECONDS).stream()
                .map(GroupListing::groupId)
                .filter(groupId -> groupId.startsWith(GROUP_PREFIX))
                .toList();
    }

    /** Whether the group has committed past the end of every observed partition it is assigned. */
    private boolean applied(ConsumerGroupDescription group, Map<TopicPartition, Long> ends)
            throws InterruptedException, ExecutionException, TimeoutException {
        var assigned = group.members().stream()
                .flatMap(member -> member.assignment().topicPartitions().stream())
                .filter(ends::containsKey)
                .toList();
        if (assigned.isEmpty()) {
            return !group.members().isEmpty();
        }
        var committed = admin.listConsumerGroupOffsets(group.groupId())
                .partitionsToOffsetAndMetadata(group.groupId())
                .get(BROKER_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        return assigned.stream().allMatch(partition -> {
            var offset = committed.get(partition);
            return (offset == null ? 0L : offset.offset()) >= ends.get(partition);
        });
    }

    @Override
    public void close() {
        admin.close(BROKER_TIMEOUT);
    }
}
