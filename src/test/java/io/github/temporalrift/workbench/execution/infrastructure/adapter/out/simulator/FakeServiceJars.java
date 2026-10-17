package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Writes executable-jar layouts with a start class and some resources, without compiling a service. */
final class FakeServiceJars {

    private FakeServiceJars() {}

    /** @param resources contents by path below {@code BOOT-INF/classes/} */
    static Path write(Path directory, String name, String startClass, Map<String, String> resources) {
        return writeEntries(directory, name, startClass, resources, Map.of());
    }

    /** @param entries contents by full entry name, written as they are */
    static Path writeEntries(
            Path directory,
            String name,
            String startClass,
            Map<String, String> resources,
            Map<String, String> entries) {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (startClass != null) {
            manifest.getMainAttributes().putValue("Start-Class", startClass);
        }
        var jar = directory.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            resources.forEach((path, content) -> put(out, "BOOT-INF/classes/" + path, content));
            entries.forEach((path, content) -> put(out, path, content));
            put(out, "BOOT-INF/lib/library.jar", "not really a jar");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return jar;
    }

    private static void put(JarOutputStream out, String entry, String content) {
        try {
            out.putNextEntry(new JarEntry(entry));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
