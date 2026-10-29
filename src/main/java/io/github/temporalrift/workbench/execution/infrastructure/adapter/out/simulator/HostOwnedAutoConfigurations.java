package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.micrometer.metrics.autoconfigure.logging.logback.LogbackMetricsAutoConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.MapPropertySource;

/**
 * Leaves out of a hosted service the auto-configurations that would instrument what the workbench owns for the whole
 * JVM. Logging is one: a service's logging metrics would count the events of every service and of the workbench, and
 * the listener they register with the shared logging context stays there after the service closes, holding the
 * service's meter registry and with it the service's class loader.
 *
 * <p>The exclusions are added to those the service's own configuration makes, which a property of the host would
 * otherwise replace, and only for the auto-configurations the service would apply: excluding one it does not list is
 * an error.
 */
final class HostOwnedAutoConfigurations implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final String EXCLUDE = "spring.autoconfigure.exclude";
    private static final List<String> HOST_OWNED = List.of(LogbackMetricsAutoConfiguration.class.getName());
    private static final String SOURCE = "simulatorHostOwnedAutoConfigurations";

    private final ClassLoader service;

    HostOwnedAutoConfigurations(ClassLoader service) {
        this.service = service;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        var environment = event.getEnvironment();
        var candidates = ImportCandidates.load(AutoConfiguration.class, service).getCandidates();
        var excluded = new LinkedHashSet<>(Binder.get(environment)
                .bind(EXCLUDE, Bindable.listOf(String.class))
                .orElse(List.of()));
        HOST_OWNED.stream().filter(candidates::contains).forEach(excluded::add);
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(SOURCE, Map.of(EXCLUDE, String.join(",", excluded))));
    }
}
