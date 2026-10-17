package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator.fixture;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stands in for a gameplay service in the simulator's tests: a web application that answers every request with
 * {@code 404} and exposes the {@code simulationEventDelivery} entry, recording what it is handed.
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class})
public class FakeServiceApplication {

    /** What each fake service was handed, as {@code name:payload}. */
    public static final List<String> DELIVERED = new CopyOnWriteArrayList<>();

    @Bean("simulationEventDelivery")
    Consumer<Map<String, Object>> simulationEventDelivery(@Value("${fake.name}") String name) {
        return message ->
                DELIVERED.add(name + ":" + new String((byte[]) message.get("payload"), StandardCharsets.UTF_8));
    }
}
