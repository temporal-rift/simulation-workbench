package io.github.temporalrift.workbench;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import io.github.temporalrift.workbench.execution.application.command.ExecutionSettings;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.BaselineRulesCatalog;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;

/** Replaces the real lanes with scripted in-process games so runs execute without any deployed service. */
@TestConfiguration(proxyBeanMethods = false)
public class ExecutionTestConfiguration {

    /** The bundle of the default test manifest's variants, served by two of the scripted lanes. */
    public static final BundleDigests DEFAULT_BUNDLE = new BundleDigests("a".repeat(64), "b".repeat(64));

    /** The bundle of the test manifests' second rules variant, served by the third scripted lane. */
    public static final BundleDigests CANDIDATE_BUNDLE = new BundleDigests("f".repeat(64), "b".repeat(64));

    @Bean
    @Primary
    ScriptedLanes scriptedLanes(CommandLedger ledger, EvidenceLedger evidence, Clock clock) {
        return new ScriptedLanes(ledger, evidence, clock, List.of(DEFAULT_BUNDLE, DEFAULT_BUNDLE, CANDIDATE_BUNDLE));
    }

    /** The baseline document of the scripted lanes, named by {@link VariantRulesFixtures#ARTIFACT_DIGEST}. */
    @Bean
    @Primary
    BaselineRulesCatalog scriptedBaselineRules() {
        return VariantRulesFixtures.catalog();
    }

    @Bean
    StatementCounter statementCounter() {
        return new StatementCounter();
    }

    @Bean
    ExecuteListenerProvider statementCounterProvider(StatementCounter counter) {
        return new DefaultExecuteListenerProvider(counter);
    }

    /** A heartbeat of one millisecond makes every checkpoint renew the lease and look for cancellation. */
    @Bean
    @Primary
    ExecutionSettings testExecutionSettings() {
        return new ExecutionSettings(
                Duration.ofSeconds(30), Duration.ofMillis(1), 2, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
