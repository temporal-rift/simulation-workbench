package io.github.temporalrift.workbench;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL and one Kafka container per test JVM, shared by every Spring context in it. Each distinct context
 * (a security slice, a simulator test with its own slot count) would otherwise start a pair of containers of its own,
 * and starting Kafka is the slowest step of a context. The containers are stopped with the JVM, never with a context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("temporal_rift")
            .withUsername("temporal_rift")
            .withPassword("temporal_rift");

    @SuppressWarnings("resource")
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return KAFKA;
    }
}
