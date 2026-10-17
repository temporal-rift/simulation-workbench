package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceClassLoaderTest {

    @TempDir
    Path directory;

    @Test
    void theServicesOwnResourceWinsOverTheHostsAndTheHostsStillShowsInTheFullList() throws IOException {
        var hosted = hosted(Map.of("application.yml", "service: true"));
        var hostDirectory = Files.createDirectory(directory.resolve("host"));
        Files.writeString(hostDirectory.resolve("application.yml"), "host: true");

        try (var host = new URLClassLoader(new URL[] {hostDirectory.toUri().toURL()}, null);
                var loader = new ServiceClassLoader(hosted, host)) {
            try (var content = loader.getResource("application.yml").openStream()) {
                assertThat(new String(content.readAllBytes())).isEqualTo("service: true");
            }
            assertThat(Collections.list(loader.getResources("application.yml"))).hasSize(2);
        }
    }

    @Test
    void aResourceOnlyTheHostHasIsStillFound() throws IOException {
        var hosted = hosted(Map.of());
        var hostDirectory = Files.createDirectory(directory.resolve("host"));
        Files.writeString(hostDirectory.resolve("shared.txt"), "shared");

        try (var host = new URLClassLoader(new URL[] {hostDirectory.toUri().toURL()}, null);
                var loader = new ServiceClassLoader(hosted, host)) {
            assertThat(loader.getResource("shared.txt")).isNotNull();
        }
    }

    @Test
    void frameworkAndJdkClassesComeFromTheHostSoTheSlotSharesOneSpring() throws Exception {
        var hosted = hosted(Map.of());

        try (var loader = new ServiceClassLoader(hosted, getClass().getClassLoader())) {
            assertThat(loader.loadClass("java.lang.String")).isSameAs(String.class);
            assertThat(loader.loadClass(org.springframework.context.ApplicationContext.class.getName()))
                    .isSameAs(org.springframework.context.ApplicationContext.class);
        }
    }

    @Test
    void theWorkbenchsOwnClassesAreNeverTakenFromAService() throws Exception {
        var hosted = hosted(Map.of());

        try (var loader = new ServiceClassLoader(hosted, getClass().getClassLoader())) {
            assertThat(loader.loadClass(ServiceClassLoader.class.getName())).isSameAs(ServiceClassLoader.class);
        }
    }

    private ServiceJar hosted(Map<String, String> resources) {
        var jar = FakeServiceJars.write(directory, "service.jar", "x.Main", resources);
        return ServiceJar.open(jar, directory.resolve("work"));
    }
}
