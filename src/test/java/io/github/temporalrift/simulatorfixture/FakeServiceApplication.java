package io.github.temporalrift.simulatorfixture;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /** The per-player rate limit each fake service ended up with, by name; {@code unset} when none reached it. */
    public static final Map<String, String> RATE_LIMITS = new ConcurrentHashMap<>();

    @Bean("simulationEventDelivery")
    Consumer<Map<String, Object>> simulationEventDelivery(
            @Value("${fake.name}") String name,
            @Value("${game.rate-limit.requests-per-minute:unset}") String rateLimit) {
        RATE_LIMITS.put(name, rateLimit);
        return message ->
                DELIVERED.add(name + ":" + new String((byte[]) message.get("payload"), StandardCharsets.UTF_8));
    }
}
