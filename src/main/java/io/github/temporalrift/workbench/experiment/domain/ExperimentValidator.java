package io.github.temporalrift.workbench.experiment.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;

/**
 * Validates a frozen experiment manifest against the published boundary rules. Structural problems
 * yield {@code INVALID_EXPERIMENT}; malformed portable artifact references (digests, image digests,
 * source revisions, contract versions) yield {@code MANIFEST_MISMATCH}.
 */
public final class ExperimentValidator {

    private static final Set<String> FACTIONS = Set.of("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS");
    private static final Set<Integer> PLAYER_COUNTS = Set.of(3, 4, 5);
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern IMAGE_DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final Pattern SOURCE_REVISION = Pattern.compile("^[a-f0-9]{7,64}$");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$");
    private static final Pattern UINT64 = Pattern.compile("^(0|[1-9]\\d{0,19})$");
    private static final BigInteger MAX_UINT64 = new BigInteger("18446744073709551615");
    private static final String SERVICES_PREFIX = "services.";
    private static final String SEAT_INDEX_FIELD = "seatIndex";
    private static final String POLICY_ID_FIELD = "policyId";
    private static final String POLICY_VERSION_FIELD = "policyVersion";
    private static final List<String> SECRET_KEYS = List.of(
            "password",
            "passwd",
            "secret",
            "token",
            "credentials",
            "authorization",
            "private_key",
            "privatekey",
            "api_key",
            "apikey",
            "client_secret");
    private static final List<String> SECRET_VALUE_MARKERS = List.of("-----begin", "ghp_", "gho_", "xoxb-", "sk-");
    private static final List<String> LOCAL_PATH_MARKERS =
            List.of("file://", "/home/", "/root/", "/tmp/", "/var/", "c:\\", "c:/", "c:\\\\", "${home}", "%home%");

    private ExperimentValidator() {}

    /** Structural validation only; policy references are not resolved against defined bundles. */
    public static ManifestView validate(JsonNode manifest) {
        return validate(manifest, null);
    }

    /** Structural validation plus resolution of every policy reference through the verifier. */
    public static ManifestView validate(JsonNode manifest, PolicyReferenceVerifier policyVerifier) {
        if (manifest == null || !manifest.isObject()) {
            throw invalid("manifest must be a JSON object");
        }
        require(manifest.has("schemaVersion"), "schemaVersion is required");
        if (manifest.get("schemaVersion").asInt(-1) != 1) {
            throw invalid("schemaVersion must be 1");
        }
        var name = text(manifest, "name");
        if (name == null || name.isBlank() || name.length() > 120) {
            throw invalid("name must be 1..120 characters");
        }
        var variants = validateVariants(manifest.get("variants"));
        validateServices(manifest.get("services"));
        validateContracts(manifest.get("contracts"));
        var policies = validatePolicies(manifest.get("policies"), policyVerifier);
        var seeds = validateSeeds(manifest.get("seeds"));
        var playerCounts = validatePlayerCounts(manifest.get("playerCounts"));
        var factionSets = validateFactionSets(manifest.get("factionSets"), playerCounts);
        validateEnum(manifest, "seatRotationMode", "CYCLIC");
        validateEnum(manifest, "timingMode", "LOGICAL");
        validatePositiveInt(manifest, "concurrency");
        validatePositiveInt(manifest, "caseWallTimeoutSeconds");
        validatePositiveInt(manifest, "maxRejectedCandidatesPerWindow");
        rejectSecretsAndLocalPaths(manifest);
        return new ManifestView(name, variants, policies, seeds, playerCounts, factionSets);
    }

    private static List<String> validateVariants(JsonNode variants) {
        if (variants == null || !variants.isArray() || variants.isEmpty()) {
            throw invalid("variants must be a nonempty array");
        }
        var labels = new ArrayList<String>();
        var seen = new HashSet<String>();
        variants.forEach(variant -> {
            var label = text(variant, "label");
            if (label == null || label.isBlank()) {
                throw invalid("variant label must be a nonempty string");
            }
            if (!seen.add(label)) {
                throw invalid("variant labels must be unique: " + label);
            }
            labels.add(label);
            artifact(variant.get("rulesArtifact"), "rulesArtifact");
            artifact(variant.get("contentArtifact"), "contentArtifact");
            digest(text(variant, "effectiveRulesDigest"), "effectiveRulesDigest");
            digest(text(variant, "effectiveContentDigest"), "effectiveContentDigest");
        });
        return List.copyOf(labels);
    }

    private static void artifact(JsonNode artifact, String field) {
        if (artifact == null || !artifact.isObject()) {
            throw invalid(field + " is required");
        }
        digest(text(artifact, "digest"), field + ".digest");
    }

    private static String digest(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw mismatch(field + " must be a lowercase SHA-256 hex digest");
        }
        return value;
    }

    private static void validateServices(JsonNode services) {
        if (services == null || !services.isObject()) {
            throw invalid("services is required");
        }
        for (String service : List.of("gameService", "timelineService", "readService")) {
            var node = services.get(service);
            if (node == null || !node.isObject()) {
                throw invalid(SERVICES_PREFIX + service + " is required");
            }
            var image = text(node, "imageDigest");
            if (image == null || !IMAGE_DIGEST.matcher(image).matches()) {
                throw mismatch(SERVICES_PREFIX + service + ".imageDigest must match sha256:<hex>");
            }
            var revision = text(node, "sourceRevision");
            if (revision == null || !SOURCE_REVISION.matcher(revision).matches()) {
                throw mismatch(SERVICES_PREFIX + service + ".sourceRevision must be 7..64 lowercase hex");
            }
        }
    }

    private static void validateContracts(JsonNode contracts) {
        if (contracts == null || !contracts.isObject() || contracts.isEmpty()) {
            throw invalid("contracts must carry at least one pinned module version");
        }
        for (var entry : contracts.properties()) {
            if (!entry.getValue().isTextual()
                    || !SEMVER.matcher(entry.getValue().asString()).matches()) {
                throw mismatch("contracts." + entry.getKey() + " must be an exact semantic version");
            }
        }
    }

    private static List<PolicyRef> validatePolicies(JsonNode policies, PolicyReferenceVerifier verifier) {
        if (policies == null || !policies.isArray() || policies.isEmpty()) {
            throw invalid("policies must be a nonempty array");
        }
        var refs = new ArrayList<PolicyRef>();
        policies.forEach(policy -> refs.add(validatePolicy(policy, verifier)));
        return List.copyOf(refs);
    }

    private static PolicyRef validatePolicy(JsonNode policy, PolicyReferenceVerifier verifier) {
        var id = text(policy, "id");
        var version = text(policy, "version");
        if (id == null || id.isBlank()) {
            throw invalid("policy id must be a nonempty string");
        }
        if (version == null || version.isBlank()) {
            throw invalid("policy version must be a nonempty string");
        }
        var artifactDigest = digest(text(policy, "artifactDigest"), "policy artifactDigest");
        var parameters = policy.get("parameters");
        if (parameters == null || !parameters.isObject()) {
            throw invalid("policy parameters must be an object");
        }
        if (verifier != null) {
            verifyReference(verifier, id, version, artifactDigest, parameters.size());
        }
        validateSeatAssignment(policy.get("seatAssignment"));
        return new PolicyRef(id, version);
    }

    private static void verifyReference(
            PolicyReferenceVerifier verifier, String id, String version, String artifactDigest, int parameterCount) {
        switch (verifier.verify(id, version, artifactDigest, parameterCount)) {
            case VALID -> {
                // The reference names a defined bundle; nothing to report.
            }
            case UNKNOWN_POLICY -> throw invalid("unknown policy " + id + "@" + version);
            case UNSUPPORTED_PARAMETERS -> throw invalid("policy " + id + "@" + version + " accepts no parameters");
            case DIGEST_MISMATCH ->
                throw mismatch("policy " + id + "@" + version + " artifactDigest does not match the defined bundle");
        }
    }

    private static void validateSeatAssignment(JsonNode seats) {
        if (seats == null || !seats.isArray() || seats.isEmpty()) {
            throw invalid("policy seatAssignment must be a nonempty array");
        }
        var seenSeats = new HashSet<String>();
        seats.forEach(seat -> validateSeat(seat, seenSeats));
    }

    private static void validateSeat(JsonNode seat, Set<String> seenSeats) {
        if (!seat.isObject()
                || !seat.has(SEAT_INDEX_FIELD)
                || seat.get(SEAT_INDEX_FIELD).asInt(-1) < 0) {
            throw invalid("seatAssignment seatIndex must be a nonnegative integer");
        }
        var policyId = text(seat, POLICY_ID_FIELD);
        var policyVersion = text(seat, POLICY_VERSION_FIELD);
        if (policyId == null || policyId.isBlank() || policyVersion == null || policyVersion.isBlank()) {
            throw invalid("seatAssignment policyId/policyVersion must be nonempty");
        }
        if (!seenSeats.add(seat.get(SEAT_INDEX_FIELD).asInt() + "|" + policyId + "|" + policyVersion)) {
            throw invalid("policy seatAssignment entries must be unique");
        }
    }

    private static List<String> validateSeeds(JsonNode seeds) {
        if (seeds == null || !seeds.isArray() || seeds.isEmpty()) {
            throw invalid("seeds must be a nonempty array");
        }
        var values = new ArrayList<String>();
        var seen = new HashSet<String>();
        seeds.forEach(seed -> {
            if (!seed.isTextual()) {
                throw invalid("seeds must be decimal uint64 strings");
            }
            var value = seed.asString();
            if (!UINT64.matcher(value).matches() || new BigInteger(value).compareTo(MAX_UINT64) > 0) {
                throw invalid("seed out of uint64 range: " + value);
            }
            if (!seen.add(value)) {
                throw invalid("seeds must be unique: " + value);
            }
            values.add(value);
        });
        return List.copyOf(values);
    }

    private static Set<Integer> validatePlayerCounts(JsonNode counts) {
        if (counts == null || !counts.isArray() || counts.isEmpty()) {
            throw invalid("playerCounts must be a nonempty array");
        }
        var values = new HashSet<Integer>();
        counts.forEach(count -> {
            if (!count.isInt() && !count.isLong()) {
                throw invalid("playerCounts must be integers");
            }
            int value = count.asInt();
            if (!PLAYER_COUNTS.contains(value)) {
                throw invalid("playerCounts must be a subset of [3, 4, 5]: " + value);
            }
            if (!values.add(value)) {
                throw invalid("playerCounts must be unique: " + value);
            }
        });
        return Set.copyOf(values);
    }

    private static List<List<String>> validateFactionSets(JsonNode sets, Set<Integer> playerCounts) {
        if (sets == null || !sets.isArray() || sets.isEmpty()) {
            throw invalid("factionSets must be a nonempty array");
        }
        var normalized = new ArrayList<List<String>>();
        var seen = new HashSet<String>();
        sets.forEach(set -> {
            if (set == null || !set.isArray() || set.size() < 3 || set.size() > 5) {
                throw invalid("every faction set must carry 3..5 factions");
            }
            var factions = new ArrayList<String>();
            set.forEach(faction -> {
                if (!faction.isTextual() || !FACTIONS.contains(faction.asString())) {
                    throw invalid("unknown faction: " + faction);
                }
                factions.add(faction.asString());
            });
            if (new HashSet<>(factions).size() != factions.size()) {
                throw invalid("faction set carries a duplicate faction: " + factions);
            }
            if (!playerCounts.contains(factions.size())) {
                throw invalid("faction set size " + factions.size() + " is not a selected player count");
            }
            var sorted = factions.stream().sorted().toList();
            if (!seen.add(String.join(",", sorted))) {
                throw invalid("factionSets must be unique: " + factions);
            }
            normalized.add(sorted);
        });
        return List.copyOf(normalized);
    }

    private static void validateEnum(JsonNode manifest, String field, String expected) {
        if (!manifest.has(field) || !expected.equals(text(manifest, field))) {
            throw invalid(field + " must be " + expected);
        }
    }

    private static void validatePositiveInt(JsonNode manifest, String field) {
        var node = manifest.get(field);
        if (node == null || !node.isInt() || node.asInt() < 1) {
            throw invalid(field + " must be a positive integer");
        }
    }

    static void rejectSecretsAndLocalPaths(JsonNode manifest) {
        inspect(manifest);
    }

    private static void inspect(JsonNode node) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                rejectSecretFieldName(entry.getKey());
                inspectValue(entry.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(ExperimentValidator::inspectValue);
        }
    }

    private static void inspectValue(JsonNode value) {
        if (value.isTextual()) {
            rejectSecretOrLocalValue(value.asString());
        } else {
            inspect(value);
        }
    }

    private static void rejectSecretFieldName(String fieldName) {
        var segments = new HashSet<>(List.of(fieldName.toLowerCase().split("[^a-z0-9]+")));
        for (String key : SECRET_KEYS) {
            var keySegments = List.of(key.split("[^a-z0-9]+"));
            if (segments.containsAll(keySegments)) {
                throw invalid("manifest must not carry credentials or secrets: " + fieldName);
            }
        }
    }

    private static void rejectSecretOrLocalValue(String value) {
        var lower = value.toLowerCase();
        for (String marker : SECRET_VALUE_MARKERS) {
            if (lower.startsWith(marker)) {
                throw invalid("manifest must not carry credentials or secrets");
            }
        }
        for (String marker : LOCAL_PATH_MARKERS) {
            if (lower.startsWith(marker)) {
                throw invalid("manifest must not carry machine-local paths: " + marker);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        var value = node.get(field);
        return value.isTextual() ? value.asString() : null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private static ExperimentValidationException invalid(String message) {
        return new ExperimentValidationException(ExperimentErrorCode.INVALID_EXPERIMENT, message);
    }

    private static ExperimentValidationException mismatch(String message) {
        return new ExperimentValidationException(ExperimentErrorCode.MANIFEST_MISMATCH, message);
    }

    /** Validated, normalized view of a manifest for freeze and matrix enumeration. */
    public record ManifestView(
            String name,
            List<String> variantLabels,
            List<PolicyRef> policies,
            List<String> seeds,
            Set<Integer> playerCounts,
            List<List<String>> factionSets) {}

    /** Homogeneous policy reference applied to every seat of a case. */
    public record PolicyRef(String id, String version) {}
}
