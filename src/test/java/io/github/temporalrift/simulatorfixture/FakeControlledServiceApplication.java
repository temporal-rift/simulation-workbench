package io.github.temporalrift.simulatorfixture;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for a gameplay service that serves the simulation execution controls: a configuration is adopted and
 * answered with a matching checkpoint, unless {@code fake.leftover} says the service still holds an earlier case, and
 * every close succeeds unless {@code fake.close-fails} says otherwise. Everything else answers {@code 404}, so a case
 * fails right after it is configured.
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class})
public class FakeControlledServiceApplication {

    /** How many contexts were closed, over every fake service. */
    public static final AtomicInteger CLOSES = new AtomicInteger();

    /** The entry a slot routes its services' records through; these stand-ins consume nothing. */
    @Bean("simulationEventDelivery")
    Consumer<Map<String, Object>> simulationEventDelivery() {
        return _ -> {};
    }

    @RestController
    static final class Controls {

        private static final JsonMapper JSON = JsonMapper.builder().build();
        private final boolean leftover;
        private final boolean closeFails;

        Controls(
                @Value("${fake.leftover:false}") boolean leftover,
                @Value("${fake.close-fails:false}") boolean closeFails) {
            this.leftover = leftover;
            this.closeFails = closeFails;
        }

        @PutMapping("/internal/simulation/v1/execution")
        ResponseEntity<String> configure(@RequestBody String body) {
            if (leftover) {
                return ResponseEntity.status(409)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":409,\"title\":\"Conflict\",\"code\":\"EXECUTION_STATE_NOT_CLEAN\"}");
            }
            var context = JSON.readTree(body);
            var checkpoint = JSON.createObjectNode()
                    .put("caseKey", context.get("caseKey").asString())
                    .put("manifestDigest", context.get("manifestDigest").asString())
                    .put("rulesDigest", context.get("rulesDigest").asString())
                    .put("revision", 0)
                    .put("logicalTime", context.get("logicalTime").asString())
                    .putNull("gameId")
                    .put("state", "READY")
                    .put("drained", true)
                    .put("outboxPending", 0)
                    .put("continuationsPending", 0)
                    .put("dueTimersPending", 0)
                    .putNull("nextDeadline");
            checkpoint.putArray("sourceWatermarks");
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(checkpoint.toString());
        }

        @DeleteMapping("/internal/simulation/v1/execution")
        ResponseEntity<Void> close() {
            CLOSES.incrementAndGet();
            return closeFails
                    ? ResponseEntity.internalServerError().build()
                    : ResponseEntity.noContent().build();
        }
    }
}
