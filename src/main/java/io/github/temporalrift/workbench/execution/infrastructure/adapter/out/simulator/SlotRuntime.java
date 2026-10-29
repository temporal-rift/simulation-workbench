package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;

/**
 * The three services of one slot, started in this JVM under profile {@code in-memory} as children of a parent context
 * that supplies what they cannot get from a deployment: the event sink their publications leave through, the rules
 * they play under, and the issuer whose tokens they trust. They listen on loopback ports of the operating system's
 * choosing and keep all state in their contexts, so closing the runtime leaves nothing behind.
 */
final class SlotRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SlotRuntime.class);

    /** Spring Boot DevTools' listener, present only while the workbench runs from its development classpath. */
    private static final String RESTART_LISTENER =
            "org.springframework.boot.devtools.restart.RestartApplicationListener";

    /** The builds a slot hosts and the rules it plays under. */
    record Build(ServiceJar game, ServiceJar timeline, ServiceJar read, PropertySource<?> rules) {}

    /** A started service and the class loader that holds its jar open until the service is gone. */
    private record Hosted(ConfigurableApplicationContext context, ServiceClassLoader loader) {}

    private final SlotEventRouter router;
    private final AnnotationConfigApplicationContext parent;
    private final List<Hosted> children;
    private final String gameServiceUrl;
    private final String timelineServiceUrl;
    private final String readServiceUrl;
    private final String operatorToken;
    private final List<LaneEndpoints.BotIdentity> bots;

    private SlotRuntime(
            SlotEventRouter router,
            AnnotationConfigApplicationContext parent,
            List<Hosted> children,
            List<String> urls,
            String operatorToken,
            List<LaneEndpoints.BotIdentity> bots) {
        this.router = router;
        this.parent = parent;
        this.children = children;
        this.gameServiceUrl = urls.get(0);
        this.timelineServiceUrl = urls.get(1);
        this.readServiceUrl = urls.get(2);
        this.operatorToken = operatorToken;
        this.bots = bots;
    }

    /**
     * Starts the three services and begins routing their records.
     *
     * @param botCount how many bot identities to mint; they are the same players in every slot
     */
    static SlotRuntime start(Build build, int botCount, Clock clock) {
        return start(build, SimulatorBots.derived(botCount), clock);
    }

    /**
     * Starts the three services and begins routing their records.
     *
     * @param botPlayerIds the player id of each bot identity to mint, in seat order
     */
    static SlotRuntime start(Build build, List<UUID> botPlayerIds, Clock clock) {
        SharedFramework.prepare();
        var router = new SlotEventRouter();
        var issuer = new SimulatorIssuer(clock);
        var parent = new AnnotationConfigApplicationContext();
        parent.getEnvironment().getPropertySources().addFirst(build.rules());
        parent.registerBean(
                "simulationEventSink", Consumer.class, () -> (Consumer<Map<String, Object>>) router::publish);
        parent.registerBean(JwtDecoder.class, issuer::decoder);
        parent.refresh();
        var children = new ArrayList<Hosted>();
        try (var startup = new Startup(router, parent, children)) {
            var read = host(build.read(), parent, children, "game.simulation.enabled=true");
            var timeline = host(build.timeline(), parent, children, "game.simulation.enabled=true");
            var game = host(build.game(), parent, children, "game.simulation.enabled=true");
            router.bind(Map.of(
                    SlotEventRouter.GAME_SERVICE, delivery(game),
                    SlotEventRouter.TIMELINE_SERVICE, delivery(timeline),
                    SlotEventRouter.READ_SERVICE, delivery(read)));
            var bots = botPlayerIds.stream()
                    .map(playerId -> new LaneEndpoints.BotIdentity(playerId, issuer.bot(playerId)))
                    .toList();
            var runtime = new SlotRuntime(
                    router, parent, children, List.of(url(game), url(timeline), url(read)), issuer.operator(), bots);
            startup.succeeded();
            return runtime;
        }
    }

    /** Stops whatever a startup brought up unless it succeeded. */
    private static final class Startup implements AutoCloseable {
        private final SlotEventRouter router;
        private final AnnotationConfigApplicationContext parent;
        private final List<Hosted> children;
        private boolean succeeded;

        private Startup(SlotEventRouter router, AnnotationConfigApplicationContext parent, List<Hosted> children) {
            this.router = router;
            this.parent = parent;
            this.children = children;
        }

        private void succeeded() {
            succeeded = true;
        }

        @Override
        public void close() {
            if (!succeeded) {
                SlotRuntime.close(router, parent, children);
            }
        }
    }

    private static ConfigurableApplicationContext host(
            ServiceJar jar, ConfigurableApplicationContext parent, List<Hosted> started, String... properties) {
        var loader = new ServiceClassLoader(jar, SlotRuntime.class.getClassLoader());
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        var running = false;
        try {
            var builder = new SpringApplicationBuilder(loader.loadClass(jar.startClass()))
                    .parent(parent)
                    .profiles("in-memory")
                    .resourceLoader(new DefaultResourceLoader(loader))
                    .bannerMode(Banner.Mode.OFF)
                    .registerShutdownHook(false)
                    .properties(properties);
            // The workbench has configured logging and owns restarting the JVM's application; a hosted service must
            // do neither for the whole JVM, and the restarter would keep the class loader of the first to try.
            var application = builder.application();
            application.setListeners(application.getListeners().stream()
                    .filter(listener -> !(listener instanceof LoggingApplicationListener))
                    .filter(listener ->
                            !RESTART_LISTENER.equals(listener.getClass().getName()))
                    .toList());
            application.addListeners(new HostOwnedAutoConfigurations(loader));
            // Arguments, because a service's own application.yml outranks the defaults that properties() sets.
            var context = builder.run("--server.port=0", "--server.address=127.0.0.1");
            started.add(new Hosted(context, loader));
            running = true;
            return context;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The start class " + jar.startClass() + " is not in its jar", e);
        } finally {
            thread.setContextClassLoader(previous);
            if (!running) {
                closeLoader(loader);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Map<String, Object>> delivery(ConfigurableApplicationContext context) {
        return context.getBean("simulationEventDelivery", Consumer.class);
    }

    private static String url(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:"
                + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    SlotEventRouter router() {
        return router;
    }

    String gameServiceUrl() {
        return gameServiceUrl;
    }

    String timelineServiceUrl() {
        return timelineServiceUrl;
    }

    String readServiceUrl() {
        return readServiceUrl;
    }

    String operatorToken() {
        return operatorToken;
    }

    List<LaneEndpoints.BotIdentity> bots() {
        return bots;
    }

    /** The class loaders the services were hosted in, last started last. */
    List<ClassLoader> classLoaders() {
        return children.stream().<ClassLoader>map(Hosted::loader).toList();
    }

    @Override
    public void close() {
        close(router, parent, children);
    }

    /** Stops the services last started first, then everything they depended on; one failing close stops no other. */
    private static void close(
            SlotEventRouter router, AnnotationConfigApplicationContext parent, List<Hosted> children) {
        for (var child : children.reversed()) {
            closeQuietly(child.context());
            closeLoader(child.loader());
        }
        closeQuietly(parent);
        router.close();
    }

    private static void closeLoader(ServiceClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            LOG.warn("A hosted service's class loader did not close cleanly: {}", e.getMessage());
        } finally {
            SharedFramework.release();
        }
    }

    private static void closeQuietly(ConfigurableApplicationContext context) {
        try {
            context.close();
        } catch (RuntimeException e) {
            LOG.warn("A hosted service did not close cleanly: {}", e.getMessage());
        }
    }
}
