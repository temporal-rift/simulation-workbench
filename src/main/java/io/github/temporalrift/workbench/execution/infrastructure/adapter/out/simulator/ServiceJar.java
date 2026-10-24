package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * A service's executable jar, extracted once into its layout of classes and libraries so a class loader can host it.
 * The extraction is keyed by the jar's digest, so a rebuilt jar never reuses another build's files.
 */
final class ServiceJar {

    private static final String CLASSES = "BOOT-INF/classes/";
    private static final String LIB = "BOOT-INF/lib/";
    private static final String COMPLETE = ".extracted";

    private final String digest;
    private final String startClass;
    private final List<URL> classpath;

    private ServiceJar(String digest, String startClass, List<URL> classpath) {
        this.digest = digest;
        this.startClass = startClass;
        this.classpath = classpath;
    }

    /**
     * @param workDirectory where extractions are kept
     * @throws IllegalArgumentException when the file is not an executable Spring Boot jar
     * @throws UncheckedIOException when it cannot be read or extracted
     */
    static ServiceJar open(Path jar, Path workDirectory) {
        try {
            var digest = digestOf(jar);
            var root = workDirectory.resolve(digest);
            if (!Files.exists(root.resolve(COMPLETE))) {
                extract(jar, workDirectory, root);
            }
            return new ServiceJar(digest, startClassOf(jar), classpathOf(root));
        } catch (IOException e) {
            throw new UncheckedIOException("The jar " + jar + " could not be read: " + e.getMessage(), e);
        }
    }

    /** {@code sha256:} and the digest of the jar file: the identity of the build a slot runs. */
    String imageDigest() {
        return "sha256:" + digest;
    }

    String startClass() {
        return startClass;
    }

    List<URL> classpath() {
        return classpath;
    }

    private static String digestOf(Path jar) throws IOException {
        try (InputStream in = Files.newInputStream(jar)) {
            return Sha256.hex(in);
        }
    }

    private static String startClassOf(Path jar) throws IOException {
        try (var file = new JarFile(jar.toFile())) {
            var manifest = file.getManifest();
            var start = manifest == null ? null : manifest.getMainAttributes().getValue("Start-Class");
            if (start == null || start.isBlank()) {
                throw new IllegalArgumentException(
                        "The jar " + jar + " is not an executable Spring Boot jar: its manifest names no Start-Class");
            }
            return start;
        }
    }

    /** Extracts beside the final directory and moves it into place, so a directory with the marker is always whole. */
    private static void extract(Path jar, Path workDirectory, Path root) throws IOException {
        Files.createDirectories(workDirectory);
        var staging = Files.createTempDirectory(workDirectory, "extracting-");
        try (var file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var target = targetOf(staging, entry.getName());
                if (target == null || entry.isDirectory()) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.createFile(staging.resolve(COMPLETE));
            Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            deleteTree(staging);
            if (!Files.exists(root.resolve(COMPLETE))) {
                throw e;
            }
        }
    }

    /** Where an entry of the jar goes, or {@code null} for the entries a hosted service does not need. */
    private static Path targetOf(Path staging, String name) {
        Path target;
        if (name.startsWith(CLASSES)) {
            target = staging.resolve("classes").resolve(name.substring(CLASSES.length()));
        } else if (name.startsWith(LIB) && name.endsWith(".jar")) {
            target = staging.resolve("lib").resolve(name.substring(LIB.length()));
        } else {
            return null;
        }
        var normalized = target.normalize();
        if (!normalized.startsWith(staging)) {
            throw new IllegalArgumentException("The jar entry " + name + " leaves its directory");
        }
        return normalized;
    }

    private static List<URL> classpathOf(Path root) throws IOException {
        var urls = new ArrayList<URL>();
        urls.add(url(root.resolve("classes")));
        var lib = root.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (Stream<Path> jars = Files.list(lib)) {
                for (var path : jars.sorted().toList()) {
                    urls.add(url(path));
                }
            }
        }
        return List.copyOf(urls);
    }

    private static URL url(Path path) throws MalformedURLException {
        return path.toUri().toURL();
    }

    private static void deleteTree(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException _) {
            // Leftovers of a failed extraction are never used: only a directory with the marker is.
        }
    }
}
