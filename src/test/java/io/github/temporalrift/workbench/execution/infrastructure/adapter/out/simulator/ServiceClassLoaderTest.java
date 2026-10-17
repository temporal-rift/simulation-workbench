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
    void theAutoConfigurationsOfTheHostAreNotListedForTheService() throws IOException {
        var imports = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        var hosted = hosted(Map.of(imports, "service.Configuration", "META-INF/spring.factories", "service=true"));
        var hostFile = directory.resolve("host").resolve(imports);
        Files.createDirectories(hostFile.getParent());
        Files.writeString(hostFile, "host.Configuration");
        Files.writeString(directory.resolve("host").resolve("META-INF/spring.factories"), "host=true");

        try (var host = new URLClassLoader(
                        new URL[] {directory.resolve("host").toUri().toURL()}, null);
                var loader = new ServiceClassLoader(hosted, host)) {
            assertThat(Collections.list(loader.getResources(imports))).hasSize(1);
            assertThat(Collections.list(loader.getResources("META-INF/spring.factories")))
                    .hasSize(1);
        }
    }

    @Test
    void aServiceWithoutSpringMetadataOfItsOwnGetsTheHosts() throws IOException {
        var factories = "META-INF/spring.factories";
        var hosted = hosted(Map.of());
        var hostFile = directory.resolve("host").resolve(factories);
        Files.createDirectories(hostFile.getParent());
        Files.writeString(hostFile, "host=true");

        try (var host = new URLClassLoader(
                        new URL[] {directory.resolve("host").toUri().toURL()}, null);
                var loader = new ServiceClassLoader(hosted, host)) {
            assertThat(Collections.list(loader.getResources(factories))).hasSize(1);
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
