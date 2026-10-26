package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListGroupsResult;
import org.apache.kafka.clients.admin.MemberAssignment;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;

class KafkaReadSideOffsetsTest {

    private static final TopicPartition GAME_EVENTS = new TopicPartition("game.events", 0);
    private static final TopicPartition TIMELINE_EVENTS = new TopicPartition("timeline.events", 0);
    private static final String GROUP = "read-service.projection";

    private final Admin admin = mock(Admin.class);
    private final KafkaReadSideOffsets offsets = new KafkaReadSideOffsets(admin);

    @Test
    void aGroupCommittedAtTheEndOffsetsHasReachedThem() {
        groups(GROUP, "game-service.other");
        assigned(GROUP, GAME_EVENTS);
        committed(Map.of(GAME_EVENTS, 5L));

        assertThat(offsets.reached(Map.of(GAME_EVENTS, 5L))).isTrue();
    }

    @Test
    void aGroupOneRecordBehindHasNot() {
        groups(GROUP);
        assigned(GROUP, GAME_EVENTS);
        committed(Map.of(GAME_EVENTS, 4L));

        assertThat(offsets.reached(Map.of(GAME_EVENTS, 5L))).isFalse();
    }

    @Test
    void anAssignedPartitionWithNoCommitHoldsTheGroupBack() {
        groups(GROUP);
        assigned(GROUP, GAME_EVENTS, TIMELINE_EVENTS);
        committed(Map.of(GAME_EVENTS, 5L));

        assertThat(offsets.reached(Map.of(GAME_EVENTS, 5L, TIMELINE_EVENTS, 1L)))
                .isFalse();
    }

    @Test
    void aPartitionTheGroupIsNotAssignedDoesNotHoldItBack() {
        groups(GROUP);
        assigned(GROUP, GAME_EVENTS);
        committed(Map.of(GAME_EVENTS, 5L));

        assertThat(offsets.reached(Map.of(GAME_EVENTS, 5L, TIMELINE_EVENTS, 9L)))
                .isTrue();
    }

    @Test
    void aGroupWithNoMembersCannotBeShownToHaveReachedTheEnds() {
        groups(GROUP);
        assigned(GROUP);

        assertThat(offsets.reached(Map.of(GAME_EVENTS, 5L))).isFalse();
    }

    @Test
    void aGroupWhoseMembersHoldNoAssignmentIsRebalancingAndHoldsBack() {
        groups(GROUP);
        describe(GROUP, List.of(member()));

        assertThat(offsets.reached(Map.of(GAME_EVENTS, 5L))).isFalse();
    }

    @Test
    void aGroupAssignedOnlyOtherTopicsDoesNotHoldBack() {
        groups(GROUP);
        assigned(GROUP, new TopicPartition("other.topic", 0));

        assertThat(offsets.reached(Map.of(GAME_EVENTS, 5L))).isTrue();
    }

    @Test
    void noReadSideGroupAtAllHasNotReachedAnything() {
        groups("game-service.other");

        assertThat(offsets.reached(Map.of(GAME_EVENTS, 5L))).isFalse();
    }

    @Test
    void aBrokerThatCannotAnswerFailsTheAttempt() {
        var failed = new KafkaFutureImpl<Collection<GroupListing>>();
        failed.completeExceptionally(new TimeoutException("down"));
        var listing = mock(ListGroupsResult.class);
        when(listing.all()).thenReturn(failed);
        when(admin.listGroups()).thenReturn(listing);

        var ends = Map.of(GAME_EVENTS, 5L);

        assertThatThrownBy(() -> offsets.reached(ends)).isInstanceOf(AttemptFailedException.class);
    }

    @Test
    void closingClosesTheAdminClient() {
        offsets.close();

        verify(admin).close(any());
    }

    private void groups(String... ids) {
        var listing = mock(ListGroupsResult.class);
        Collection<GroupListing> listed = Stream.of(ids)
                .map(id -> {
                    var group = mock(GroupListing.class);
                    when(group.groupId()).thenReturn(id);
                    return group;
                })
                .toList();
        when(listing.all()).thenReturn(KafkaFuture.completedFuture(listed));
        when(admin.listGroups()).thenReturn(listing);
    }

    private static MemberDescription member(TopicPartition... partitions) {
        var assignment = mock(MemberAssignment.class);
        when(assignment.topicPartitions()).thenReturn(Set.of(partitions));
        var member = mock(MemberDescription.class);
        when(member.assignment()).thenReturn(assignment);
        return member;
    }

    private void assigned(String group, TopicPartition... partitions) {
        describe(group, partitions.length == 0 ? List.<MemberDescription>of() : List.of(member(partitions)));
    }

    private void describe(String group, List<MemberDescription> members) {
        var description = mock(ConsumerGroupDescription.class);
        when(description.groupId()).thenReturn(group);
        when(description.members()).thenReturn(members);
        var result = mock(DescribeConsumerGroupsResult.class);
        when(result.all()).thenReturn(KafkaFuture.completedFuture(Map.of(group, description)));
        when(admin.describeConsumerGroups(any(Collection.class))).thenReturn(result);
    }

    private void committed(Map<TopicPartition, Long> offsets) {
        var map = new HashMap<TopicPartition, OffsetAndMetadata>();
        offsets.forEach((partition, offset) -> map.put(partition, new OffsetAndMetadata(offset)));
        var result = mock(ListConsumerGroupOffsetsResult.class);
        when(result.partitionsToOffsetAndMetadata(anyString())).thenReturn(KafkaFuture.completedFuture(map));
        when(admin.listConsumerGroupOffsets(anyString())).thenReturn(result);
    }
}
