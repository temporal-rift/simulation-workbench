package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Hosts one service next to the workbench. Classes of the service itself and of the project artifacts it pins load
 * from its own jar, so the services never see each other's builds; everything else, the framework included, comes
 * from the workbench, so the contexts of a slot share one Spring. Resources of the service win over the workbench's,
 * so its {@code application.yml} is the one that configures it. The auto-configurations and factories Spring Boot
 * discovers are listed from the service alone: the workbench ships configurations for libraries the service does not
 * use, which must not start inside it.
 */
final class ServiceClassLoader extends URLClassLoader {

    private static final String PROJECT_PREFIX = "io.github.temporalrift.";
    private static final String WORKBENCH_PREFIX = "io.github.temporalrift.workbench.";
    private static final String SPRING_METADATA = "META-INF/spring/";
    private static final String SPRING_FACTORIES = "META-INF/spring.factories";

    static {
        registerAsParallelCapable();
    }

    ServiceClassLoader(ServiceJar jar, ClassLoader host) {
        super(jar.classpath().toArray(URL[]::new), host);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name);
            if (loaded == null && isProjectClass(name)) {
                loaded = findOwn(name);
            }
            if (loaded == null) {
                return super.loadClass(name, resolve);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> findOwn(String name) {
        try {
            return findClass(name);
        } catch (ClassNotFoundException _) {
            return null;
        }
    }

    private static boolean isProjectClass(String name) {
        return name.startsWith(PROJECT_PREFIX) && !name.startsWith(WORKBENCH_PREFIX);
    }

    @Override
    public URL getResource(String name) {
        var own = findResource(name);
        return own != null ? own : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        var all = new ArrayList<URL>(Collections.list(findResources(name)));
        if (getParent() != null && !(isOwnedByService(name) && !all.isEmpty())) {
            all.addAll(Collections.list(getParent().getResources(name)));
        }
        return Collections.enumeration(all);
    }

    /** A service that brings no such metadata of its own, such as a stand-in without Spring jars, gets the host's. */
    private static boolean isOwnedByService(String name) {
        return name.startsWith(SPRING_METADATA) || name.equals(SPRING_FACTORIES);
    }
}
