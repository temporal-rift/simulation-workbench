package io.github.temporalrift.workbench;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import io.github.temporalrift.workbench.execution.application.command.ExecutionSettings;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;

/** Replaces the real lanes with scripted in-process games so runs execute without any deployed service. */
@TestConfiguration(proxyBeanMethods = false)
public class ExecutionTestConfiguration {

    @Bean
    @Primary
    ScriptedLanes scriptedLanes(CommandLedger ledger, EvidenceLedger evidence, Clock clock) {
        return new ScriptedLanes(ledger, evidence, clock, 2);
    }

    /** A heartbeat of one millisecond makes every checkpoint renew the lease and look for cancellation. */
    @Bean
    @Primary
    ExecutionSettings testExecutionSettings() {
        return new ExecutionSettings(
                Duration.ofSeconds(30), Duration.ofMillis(1), 2, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
