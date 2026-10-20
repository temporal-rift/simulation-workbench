package io.github.temporalrift.workbench.execution.infrastructure.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import io.github.temporalrift.workbench.experiment.ManifestFormat;

/**
 * What a deployment must declare about the frozen bundle it serves, checked identically for a lane and for the
 * simulator. Every message names the property by its full {@code scope} prefix, so a failure points at the setting.
 */
final class BundleChecks {

    private BundleChecks() {}

    static void digest(String scope, String key, String value) {
        require(scope, key, value, ManifestFormat.isDigest(value), "must be 64 lowercase hex characters");
    }

    static void sourceRevision(String scope, String key, String value) {
        require(scope, key, value, ManifestFormat.isSourceRevision(value), "must be 7 to 64 lowercase hex characters");
    }

    static void document(String scope, String key, Path value) {
        if (value == null) {
            throw new IllegalArgumentException(scope + key + " is required");
        }
    }

    static void name(String scope, String key, String value) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(scope + key + " must not be blank");
        }
    }

    static SortedMap<String, String> contracts(String scope, Map<String, String> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            throw new IllegalArgumentException(scope + "contracts is required");
        }
        var sorted = new TreeMap<>(contracts);
        sorted.forEach((module, version) -> require(
                scope,
                "contracts." + module,
                version,
                ManifestFormat.isContractVersion(version),
                "must be an exact semantic version"));
        return Collections.unmodifiableSortedMap(sorted);
    }

    static void require(String scope, String key, String value, boolean valid, String format) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(scope + key + " is required");
        }
        if (!valid) {
            throw new IllegalArgumentException(scope + key + " " + format);
        }
    }
}
