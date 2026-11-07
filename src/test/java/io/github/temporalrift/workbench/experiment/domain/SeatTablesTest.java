package io.github.temporalrift.workbench.experiment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.shared.game.Faction;

/** The seat tables of a manifest: the matrix they expand to, who plays each seat, and what is refused. */
class SeatTablesTest {

    private static final String DIGEST = "a".repeat(64);

    /** One variant, seed 42, three players over every faction set, and a uniform and a mixed table. */
    private static ObjectNode mixedTables() {
        var manifest = (ObjectNode)
                ExperimentManifests.singleSeedSinglePolicySingleVariant().deepCopy();
        var counts = manifest.putArray("playerCounts");
        counts.add(3);
        var sets = manifest.putArray("factionSets");
        ExperimentManifests.fullFactionSets().forEach(set -> {
            if (set.size() == 3) {
                sets.add(set);
            }
        });
        var policies = manifest.putArray("policies");
        var random = policies.addObject();
        random.put("id", "random");
        random.put("version", "1.0.0");
        random.put("artifactDigest", "9".repeat(64));
        random.putObject("parameters");
        var greedy = policies.addObject();
        greedy.put("id", "faction-greedy");
        greedy.put("version", "1.0.0");
        greedy.put("artifactDigest", "8".repeat(64));
        greedy.putObject("parameters");
        var tables = manifest.putArray("tables");
        table(tables.addObject(), "faction-greedy", "faction-greedy", "faction-greedy", "faction-greedy");
        table(tables.addObject(), "random-seat-0", "random", "faction-greedy", "faction-greedy");
        return manifest;
    }

    private static void table(ObjectNode table, String id, String... seatPolicies) {
        table.put("id", id);
        var seats = table.putArray("seats");
        for (var index = 0; index < seatPolicies.length; index++) {
            seats.addObject()
                    .put("seatIndex", index)
                    .put("policyId", seatPolicies[index])
                    .put("policyVersion", "1.0.0");
        }
    }

    @Test
    void theMatrixIsEveryTableOverEveryFactionSetAndRotation() {
        var view = ExperimentValidator.validate(mixedTables());

        var size = MatrixPreviewService.size(view);

        assertThat(size.total()).isEqualTo(60);
        assertThat(size.breakdown())
                .containsExactly(
                        new MatrixPreviewService.Slice("only", "faction-greedy", 3, 30),
                        new MatrixPreviewService.Slice("only", "random-seat-0", 3, 30));
    }

    @Test
    void eachSeatPlaysThePolicyOfItsTablesSeatAndEachFactionSitsOnSeatZeroEqually() {
        var cases = MatrixPreviewService.preview(mixedTables(), DIGEST).stream()
                .filter(coordinate -> coordinate.tableId().equals("random-seat-0"))
                .toList();

        assertThat(cases).hasSize(30);
        cases.forEach(coordinate -> assertThat(coordinate.seats())
                .extracting(MatrixPreviewService.SeatAssignment::policyId)
                .containsExactly("random", "faction-greedy", "faction-greedy"));
        var onSeatZero = new EnumMap<Faction, Integer>(Faction.class);
        cases.forEach(
                coordinate -> onSeatZero.merge(coordinate.seats().getFirst().faction(), 1, Integer::sum));
        assertThat(onSeatZero.values()).containsOnly(6);
    }

    @Test
    void aTableMissingASeatIsRefusedAtItsSeats() {
        var manifest = mixedTables();
        manifest.withArray("tables").get(1).withArray("seats").remove(2);

        assertThat(fieldOf(manifest)).isEqualTo("tables[1].seats");
    }

    @Test
    void aBundleNoSeatUsesIsRefusedAtItsPolicyEntry() {
        var manifest = mixedTables();
        manifest.withArray("tables").remove(1);
        manifest.withArray("tables")
                .get(0)
                .withArray("seats")
                .forEach(seat -> ((ObjectNode) seat).put("policyId", "faction-greedy"));

        assertThat(fieldOf(manifest)).isEqualTo("policies[0]");
    }

    @Test
    void aRequestOfSchemaVersionOneIsRefusedButAStoredOneIsRead() {
        var legacy = (ObjectNode) mixedTables();
        legacy.put("schemaVersion", 1);
        legacy.remove("tables");
        legacy.withArray("policies").forEach(policy -> {
            var seat = ((ObjectNode) policy).putArray("seatAssignment").addObject();
            seat.put("seatIndex", 0).put("policyId", policy.get("id").asString());
            seat.put("policyVersion", "1.0.0");
        });

        assertThat(fieldOf(legacy)).isEqualTo("schemaVersion");
        var view = ExperimentValidator.validateStored(legacy);
        assertThat(view.tables())
                .extracting(ExperimentValidator.TableRef::id)
                .containsExactly("random", "faction-greedy");
        assertThat(MatrixPreviewService.preview(legacy, DIGEST))
                .extracting(MatrixPreviewService.CaseCoordinate::tableId)
                .containsOnly("random", "faction-greedy");
    }

    @Test
    void aCaseKeyOfAStoredVersionOneManifestStaysWhatItWasFrozenWith() {
        var legacy = (ObjectNode)
                ExperimentManifests.singleSeedSinglePolicySingleVariant().deepCopy();
        legacy.put("schemaVersion", 1);
        legacy.remove("tables");
        legacy.withArray("policies").forEach(policy -> {
            var seat = ((ObjectNode) policy).putArray("seatAssignment").addObject();
            seat.put("seatIndex", 0).put("policyId", policy.get("id").asString());
            seat.put("policyVersion", policy.get("version").asString());
        });

        var first = MatrixPreviewService.preview(legacy, DIGEST).getFirst();

        assertThat(first.caseKey())
                .isEqualTo(java.util.UUID.nameUUIDFromBytes((DIGEST + "|42|only|random|"
                                + legacy.get("policies").get(0).get("version").asString() + "|3|"
                                + String.join(",", first.factionSet()) + "|0")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(first.seats())
                .extracting(MatrixPreviewService.SeatAssignment::policyId)
                .containsOnly("random");
        assertThat(List.of(first.tableId())).containsExactly("random");
    }

    @Test
    void aStoredManifestFreezesItsTablesAndReadsAVersionOneOneAsOneUniformTablePerPolicy() {
        var current = stored(mixedTables());
        var legacy = (ObjectNode) mixedTables();
        legacy.put("schemaVersion", 1);
        legacy.remove("tables");
        legacy.withArray("policies").forEach(policy -> {
            var seat = ((ObjectNode) policy).putArray("seatAssignment").addObject();
            seat.put("seatIndex", 0).put("policyId", policy.get("id").asString());
            seat.put("policyVersion", "1.0.0");
        });

        var frozen = StoredManifests.bounds(current).manifest();
        var old = StoredManifests.bounds(stored(legacy)).manifest();

        assertThat(frozen.tables())
                .extracting(table -> table.get("id"))
                .containsExactly("faction-greedy", "random-seat-0");
        assertThat(old.tables()).extracting(table -> table.get("id")).containsExactly("random", "faction-greedy");
        assertThat(old.policies()).allSatisfy(policy -> assertThat(policy).doesNotContainKey("seatAssignment"));
    }

    private static io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository.StoredExperiment
            stored(JsonNode manifest) {
        return new io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository.StoredExperiment(
                java.util.UUID.randomUUID(), DIGEST, manifest.toString(), "x", java.time.Instant.EPOCH);
    }

    private static String fieldOf(JsonNode manifest) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(
                        ExperimentValidationException.class, () -> ExperimentValidator.validate(manifest))
                .field()
                .orElseThrow();
    }

    @Test
    void aValidManifestIsAccepted() {
        assertThat(ExperimentValidator.validate(mixedTables()).tables()).hasSize(2);
    }
}
