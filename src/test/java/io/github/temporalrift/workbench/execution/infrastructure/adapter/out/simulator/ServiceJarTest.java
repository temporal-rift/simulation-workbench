package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceJarTest {

    @TempDir
    Path directory;

    @Test
    void extractsTheClassesAndLibrariesAndNamesTheStartClassAndTheBuild() throws IOException {
        var jar = FakeServiceJars.write(
                directory, "game.jar", "io.github.temporalrift.game.GameServiceApplication", Map.of("a.txt", "A"));

        var opened = ServiceJar.open(jar, directory.resolve("work"));

        assertThat(opened.startClass()).isEqualTo("io.github.temporalrift.game.GameServiceApplication");
        assertThat(opened.imageDigest()).matches("sha256:[0-9a-f]{64}");
        assertThat(opened.classpath()).hasSize(2);
        try (var loader = new URLClassLoader(opened.classpath().toArray(URL[]::new), null)) {
            assertThat(new String(loader.getResourceAsStream("a.txt").readAllBytes()))
                    .isEqualTo("A");
        }
    }

    @Test
    void theSameJarKeepsItsDigestAndAnotherBuildGetsAnother() {
        var first = FakeServiceJars.write(directory, "one.jar", "x.Main", Map.of("a.txt", "A"));
        var same = FakeServiceJars.write(directory, "same.jar", "x.Main", Map.of("a.txt", "A"));
        var other = FakeServiceJars.write(directory, "other.jar", "x.Main", Map.of("a.txt", "B"));
        var work = directory.resolve("work");

        assertThat(ServiceJar.open(first, work).imageDigest())
                .isEqualTo(ServiceJar.open(same, work).imageDigest())
                .isNotEqualTo(ServiceJar.open(other, work).imageDigest());
    }

    @Test
    void aJarWithoutAStartClassIsNotAnExecutableSpringBootJar() {
        var jar = FakeServiceJars.write(directory, "plain.jar", null, Map.of());

        assertThatThrownBy(() -> ServiceJar.open(jar, directory.resolve("work")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(jar.toString())
                .hasMessageContaining("Start-Class");
    }

    @Test
    void anEntryThatLeavesItsDirectoryIsRefused() {
        var jar = FakeServiceJars.write(directory, "evil.jar", "x.Main", Map.of("../../escape.txt", "x"));

        assertThatThrownBy(() -> ServiceJar.open(jar, directory.resolve("work")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaves its directory");
        assertThat(directory.resolve("escape.txt")).doesNotExist();
    }

    @Test
    void aFileThatIsNotAJarCannotBeRead() throws IOException {
        var notAJar = Files.writeString(directory.resolve("broken.jar"), "nope");

        assertThatThrownBy(() -> ServiceJar.open(notAJar, directory.resolve("work")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(notAJar.toString());
    }

    @Test
    void anExtractionIsReusedWithoutReadingTheJarAgain() throws IOException {
        var jar = FakeServiceJars.write(directory, "game.jar", "x.Main", Map.of("a.txt", "A"));
        var work = directory.resolve("work");
        var first = ServiceJar.open(jar, work);
        var extracted = Path.of(URI.create(first.classpath().getFirst().toString()));
        Files.writeString(extracted.resolve("a.txt"), "edited in place");

        ServiceJar.open(jar, work);

        assertThat(Files.readString(extracted.resolve("a.txt"))).isEqualTo("edited in place");
    }
}
