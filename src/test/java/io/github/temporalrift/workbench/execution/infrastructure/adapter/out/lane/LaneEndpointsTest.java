package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LaneEndpointsTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void aBlankBrokerIsTheWorkbenchDefault(String blank) {
        assertThat(lane(blank).kafkaBootstrapServers()).isNull();
    }

    @Test
    void aLaneWithoutABrokerUsesTheWorkbenchDefault() {
        assertThat(lane(null).kafkaBootstrapServers()).isNull();
    }

    @Test
    void aLaneKeepsItsOwnBroker() {
        assertThat(lane("kafka-a:9092,kafka-b:9092").kafkaBootstrapServers()).isEqualTo("kafka-a:9092,kafka-b:9092");
    }

    private static LaneEndpoints lane(String broker) {
        return new LaneEndpoints(
                "lane", "http://g", "http://t", "http://r", "op", "game.events", "timeline.events", broker, List.of());
    }
}
