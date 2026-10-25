package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.experiment;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;
import io.github.temporalrift.workbench.experiment.ExperimentBounds;
import io.github.temporalrift.workbench.experiment.ExperimentCase;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;

/** Reads frozen experiments through the experiment module's public API. */
public class ExperimentSourceAdapter implements ExperimentSource {

    private final ExperimentCatalog catalog;

    public ExperimentSourceAdapter(ExperimentCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<Plan> plan(UUID experimentId) {
        return catalog.bounds(experimentId).map(ExperimentSourceAdapter::toPlan);
    }

    @Override
    public Optional<Snapshot> snapshot(UUID experimentId) {
        return catalog.snapshot(experimentId)
                .map(snapshot -> new Snapshot(
                        toPlan(snapshot.bounds()),
                        snapshot.cases().stream()
                                .map(ExperimentSourceAdapter::toPlannedCase)
                                .toList()));
    }

    @Override
    public Optional<CaseRules> rules(UUID experimentId, String variantLabel) {
        return catalog.variantRules(experimentId, variantLabel).map(rules -> {
            var properties = new TreeMap<String, Object>();
            rules.properties().forEach((key, value) -> properties.put(key, plain(value)));
            return new CaseRules(properties, rules.digest());
        });
    }

    private static Plan toPlan(ExperimentBounds bounds) {
        var services = services(bounds.manifest().services());
        var contracts = contracts(bounds.manifest().contracts());
        return new Plan(
                bounds.manifestDigest(),
                bounds.manifestJson(),
                bounds.concurrency(),
                bounds.caseWallTimeoutSeconds(),
                bounds.maxRejectedCandidatesPerWindow(),
                bounds.variants().stream()
                        .map(variant -> new Variant(
                                variant.label(),
                                new BundleDigests(variant.effectiveRulesDigest(), variant.effectiveContentDigest()),
                                LaneFit.of(
                                        variant.effectiveContentDigest(),
                                        services.get("gameService"),
                                        services.get("timelineService"),
                                        services.get("readService"),
                                        contracts)))
                        .toList());
    }

    private static Map<String, BundleProvenance.ServiceBuild> services(Map<String, Object> pinned) {
        var builds = new TreeMap<String, BundleProvenance.ServiceBuild>();
        pinned.forEach((service, build) -> {
            var fields = (Map<?, ?>) build;
            builds.put(
                    service,
                    new BundleProvenance.ServiceBuild(
                            String.valueOf(fields.get("imageDigest")), String.valueOf(fields.get("sourceRevision"))));
        });
        return builds;
    }

    private static Map<String, String> contracts(Map<String, Object> pinned) {
        var versions = new TreeMap<String, String>();
        pinned.forEach((module, version) -> versions.put(module, String.valueOf(version)));
        return versions;
    }

    /** A rules value as the number, boolean, string or list of those it is. */
    private static Object plain(JsonNode value) {
        if (value.isArray()) {
            var items = new ArrayList<Object>();
            value.forEach(item -> items.add(plain(item)));
            return items;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.asString();
    }

    private static PlannedCase toPlannedCase(ExperimentCase experimentCase) {
        return new PlannedCase(
                experimentCase.caseKey(),
                experimentCase.seed(),
                experimentCase.variantLabel(),
                experimentCase.playerCount(),
                experimentCase.seats().stream()
                        .map(seat ->
                                new SeatPlan(seat.seatIndex(), seat.faction(), seat.policyId(), seat.policyVersion()))
                        .toList());
    }
}
