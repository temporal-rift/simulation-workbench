package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.micrometer.metrics.autoconfigure.logging.logback.LogbackMetricsAutoConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class HostOwnedAutoConfigurationsTest {

    private static final String LOGBACK_METRICS = LogbackMetricsAutoConfiguration.class.getName();

    @Test
    void addsLogbackMetricsToTheExclusionsTheServiceMakesItself() {
        var environment = environment(Map.of(
                HostOwnedAutoConfigurations.EXCLUDE + "[0]", "com.example.FirstAutoConfiguration",
                HostOwnedAutoConfigurations.EXCLUDE + "[1]", "com.example.SecondAutoConfiguration"));

        prepare(environment, getClass().getClassLoader());

        assertThat(environment.getProperty(HostOwnedAutoConfigurations.EXCLUDE, String[].class))
                .containsExactly(
                        "com.example.FirstAutoConfiguration", "com.example.SecondAutoConfiguration", LOGBACK_METRICS);
    }

    @Test
    void excludesLogbackMetricsFromAServiceThatExcludesNothing() {
        var environment = environment(Map.of());

        prepare(environment, getClass().getClassLoader());

        assertThat(environment.getProperty(HostOwnedAutoConfigurations.EXCLUDE, String[].class))
                .containsExactly(LOGBACK_METRICS);
    }

    @Test
    void leavesOutWhatTheServiceWouldNotAutoConfigure() {
        var environment = environment(Map.of(HostOwnedAutoConfigurations.EXCLUDE, "com.example.OwnAutoConfiguration"));
        var listsNothing = new ClassLoader(null) {};

        prepare(environment, listsNothing);

        assertThat(environment.getProperty(HostOwnedAutoConfigurations.EXCLUDE, String[].class))
                .containsExactly("com.example.OwnAutoConfiguration");
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("service", properties));
        return environment;
    }

    private static void prepare(StandardEnvironment environment, ClassLoader service) {
        new HostOwnedAutoConfigurations(service)
                .onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                        new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment));
    }
}
