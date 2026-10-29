package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.lang.reflect.Field;
import java.util.Map;

import io.prometheus.metrics.core.util.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.kafka.support.JacksonPresent;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.ArchitecturallyEvidentType;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentLruCache;

/**
 * The state the framework keeps for the whole JVM, which the hosted services share with the workbench because their
 * framework classes are the workbench's. Some of it is created by whoever uses it first and keeps that caller's class
 * loader, and some of it is a cache that every hosted service adds to. A service's class loader reachable from either
 * stays in memory with every class of the service and everything its classes refer to, for as long as the JVM runs,
 * so every case a slot plays would leave its three services behind.
 *
 * <p>The state created on first use is therefore created by the workbench, before it hosts a service; the caches are
 * emptied whenever a hosted service's class loader is let go.
 */
final class SharedFramework {

    private static final Logger LOG = LoggerFactory.getLogger(SharedFramework.class);

    /** Created on first use, it keeps the class loader its user was given; package-private, so loaded by name. */
    private static final String MODULITH_ENDPOINT_CONFIGURATION =
            "org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration";

    /** Modulith's module model of an application, keyed by the application's class. */
    private static final CacheField APPLICATION_MODULES = new CacheField(ApplicationModules.class, "CACHE");

    /** Modulith's view of each type of an application, keyed by the imported type. */
    private static final CacheField EVIDENT_TYPES = new CacheField(ArchitecturallyEvidentType.class, "CACHE");

    /** The identifier of each event listener Modulith completed a publication for, keyed by the listener method. */
    private static final CacheField LISTENER_IDS = new CacheField(
            "org.springframework.modulith.events.support."
                    + "CompletionRegisteringAdvisor$CompletionRegisteringMethodInterceptor",
            "LISTENER_IDS");

    private static final String PRIMED = "workbench.simulator.primed";

    private static boolean prepared;

    private SharedFramework() {}

    /**
     * Creates, with the workbench's class loader as the context class loader, what the framework keeps from its first
     * user: the converter Spring Boot's binders share, whose editor of class names keeps the loader of the first
     * conversion, the Jackson detection of Spring for Apache Kafka, the resource Modulith's actuator endpoint reads,
     * and the thread Prometheus schedules its work on, which keeps the context class loader of the thread that started
     * it. Does nothing once done.
     */
    static synchronized void prepare() {
        if (prepared) {
            return;
        }
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        var workbench = SharedFramework.class.getClassLoader();
        thread.setContextClassLoader(workbench);
        try {
            new Binder(new MapConfigurationPropertySource(Map.of(PRIMED, Object.class.getName())))
                    .bind(PRIMED, Class[].class);
            JacksonPresent.isJackson2Present();
            if (ClassUtils.isPresent(MODULITH_ENDPOINT_CONFIGURATION, workbench)) {
                Class.forName(MODULITH_ENDPOINT_CONFIGURATION, true, workbench);
            }
            Scheduler.awaitInitialization();
            prepared = true;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(MODULITH_ENDPOINT_CONFIGURATION + " could not be initialized", e);
        } catch (InterruptedException e) {
            thread.interrupt();
            throw new IllegalStateException("Interrupted while starting the Prometheus scheduler", e);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Empties what Modulith keeps for every application, type and event listener it met: the module models, and the
     * identifiers of the listeners it completed publications for, a bounded cache that still keeps up to its limit of
     * hosted services. Nothing evicts the models, and the workbench computes either again on demand, so emptying them
     * when a hosted service's class loader is let go costs the workbench nothing it needs. A cache that cannot be
     * emptied is logged and left as it was.
     */
    static void release() {
        APPLICATION_MODULES.clear();
        EVIDENT_TYPES.clear();
        LISTENER_IDS.clear();
    }

    /** How many entries the caches {@link #release()} empties hold. */
    static int cached() {
        return APPLICATION_MODULES.size() + EVIDENT_TYPES.size() + LISTENER_IDS.size();
    }

    /**
     * A private static cache of a framework class, read reflectively since the framework offers no way to evict.
     *
     * @param owner the class declaring it, by name when the class is not public
     */
    private record CacheField(String owner, String name) {

        CacheField(Class<?> owner, String name) {
            this(owner.getName(), name);
        }

        void clear() {
            try {
                switch (cache()) {
                    case Map<?, ?> map -> map.clear();
                    case ConcurrentLruCache<?, ?> lru -> lru.clear();
                    case Object other -> throw new IllegalStateException("not a cache: " + other.getClass());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.warn("The cache {}.{} could not be emptied: {}", owner, name, e.toString());
            }
        }

        int size() {
            try {
                return switch (cache()) {
                    case Map<?, ?> map -> map.size();
                    case ConcurrentLruCache<?, ?> lru -> lru.size();
                    case Object other -> throw new IllegalStateException("not a cache: " + other.getClass());
                };
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        // The framework keeps these caches private and offers no way to evict them, so reflection is the only way in.
        @SuppressWarnings("java:S3011")
        private Object cache() throws ReflectiveOperationException {
            Field field = ClassUtils.forName(owner, SharedFramework.class.getClassLoader())
                    .getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        }
    }
}
