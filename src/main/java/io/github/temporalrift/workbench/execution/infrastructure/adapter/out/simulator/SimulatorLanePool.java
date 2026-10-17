package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.stream.IntStream;

import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneRoster;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ResetScheduler;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.RosterBackedLaneProvider;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.Sleeper;

/**
 * The simulator lane: slots that each host the three services in this JVM under profile {@code in-memory}. A slot
 * plays one case at a time and is made clean between cases by stopping its services, so slots are the unit of
 * parallelism. All slots host the same builds under the same rules and so serve one bundle.
 */
public final class SimulatorLanePool extends RosterBackedLaneProvider {

    static final String SLOT_PREFIX = "simulator-";
    private static final String RULES_SOURCE = "simulatorRules";

    private final Map<String, SimulatorSlot> slots;
    private final Hosting hosting;

    /** What a slot needs to play a case. */
    record Hosting(
            ApiClients clients,
            CommandLedger commands,
            EvidenceLedger evidence,
            ObjectMapper objectMapper,
            Clock clock,
            LaneEndpoints.Barrier barrier,
            Sleeper sleeper,
            BundleDigests bundle,
            BundleProvenance provenance) {}

    /**
     * What the deployment declares about the slots.
     *
     * @param rulesFile the {@code game.rules.*} properties the services play under
     * @param workDirectory where the jars are extracted
     * @param bots how many bot identities each slot mints
     */
    public record Deployment(
            int slots,
            Path gameServiceJar,
            Path timelineServiceJar,
            Path readServiceJar,
            Path rulesFile,
            Path workDirectory,
            int bots,
            BundleDigests bundle,
            Declared declared) {}

    /** The bundle provenance a deployment declares; the image of each service is the digest of its jar. */
    public record Declared(
            BundleProvenance.Artifact rulesArtifact,
            BundleProvenance.Artifact contentArtifact,
            String gameServiceRevision,
            String timelineServiceRevision,
            String readServiceRevision,
            SortedMap<String, String> contracts) {}

    /** What a slot plays with, apart from the deployment's declaration. */
    public record Collaborators(
            ApiClients clients,
            CommandLedger commands,
            EvidenceLedger evidence,
            ObjectMapper objectMapper,
            Clock clock,
            LaneEndpoints.Barrier barrier,
            Sleeper sleeper,
            ResetScheduler scheduler) {}

    private SimulatorLanePool(
            LaneRoster roster, Map<String, SimulatorSlot> slots, Hosting hosting, List<ServedBundle> servedBundles) {
        super(roster, servedBundles);
        this.slots = slots;
        this.hosting = hosting;
    }

    /**
     * Opens the jars and rules of the deployment and declares its slots. Nothing is started until a case needs a slot.
     *
     * @throws IllegalStateException naming the slot and the path when a jar or the rules file is missing or unreadable
     */
    public static SimulatorLanePool create(Deployment deployment, Collaborators with) {
        var slotIds = IntStream.rangeClosed(1, deployment.slots())
                .mapToObj(number -> SLOT_PREFIX + number)
                .toList();
        slotIds.forEach(slot -> requireReadable(slot, deployment));
        var game = ServiceJar.open(deployment.gameServiceJar(), deployment.workDirectory());
        var timeline = ServiceJar.open(deployment.timelineServiceJar(), deployment.workDirectory());
        var read = ServiceJar.open(deployment.readServiceJar(), deployment.workDirectory());
        var build = new SlotRuntime.Build(game, timeline, read, rules(deployment.rulesFile()));
        var declared = deployment.declared();
        var provenance = new BundleProvenance(
                declared.rulesArtifact(),
                declared.contentArtifact(),
                new BundleProvenance.ServiceBuild(game.imageDigest(), declared.gameServiceRevision()),
                new BundleProvenance.ServiceBuild(timeline.imageDigest(), declared.timelineServiceRevision()),
                new BundleProvenance.ServiceBuild(read.imageDigest(), declared.readServiceRevision()),
                declared.contracts());
        var slots = new LinkedHashMap<String, SimulatorSlot>();
        slotIds.forEach(id -> slots.put(id, new SimulatorSlot(id, build, deployment.bots(), with.clock())));
        var roster = new LaneRoster(
                slotIds.stream()
                        .map(id -> new LaneRoster.Member(id, deployment.bundle(), true))
                        .toList(),
                (laneId, _, _) -> slots.get(laneId).stop(),
                with.scheduler());
        var hosting = new Hosting(
                with.clients(),
                with.commands(),
                with.evidence(),
                with.objectMapper(),
                with.clock(),
                with.barrier(),
                with.sleeper(),
                deployment.bundle(),
                provenance);
        var served = ServedBundle.counted(slotIds.stream()
                .map(_ -> ServedBundle.ofLane(deployment.bundle(), provenance))
                .toList());
        return new SimulatorLanePool(roster, slots, hosting, served);
    }

    @Override
    protected CaseLane lane(LaneRoster.Tenancy tenancy) {
        return new SimulatorCaseLane(slots.get(tenancy.laneId()), tenancy, hosting);
    }

    private static void requireReadable(String slot, Deployment deployment) {
        requireReadable(slot, "game-service jar", deployment.gameServiceJar());
        requireReadable(slot, "timeline-service jar", deployment.timelineServiceJar());
        requireReadable(slot, "read-service jar", deployment.readServiceJar());
        requireReadable(slot, "rules file", deployment.rulesFile());
    }

    private static void requireReadable(String slot, String what, Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException("Simulator slot " + slot + " cannot use its " + what + " " + path
                    + ": it is missing or unreadable");
        }
    }

    /** The rules every service of a slot plays under, as the property source of a slot's parent context. */
    private static PropertySource<?> rules(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        var resource = new FileSystemResource(file);
        try {
            List<PropertySource<?>> sources;
            if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                sources = new YamlPropertySourceLoader().load(RULES_SOURCE, resource);
            } else if (name.endsWith(".properties")) {
                sources = new PropertiesPropertySourceLoader().load(RULES_SOURCE, resource);
            } else {
                throw new IllegalStateException(
                        "The simulator rules file " + file + " must be a .yml, .yaml or .properties file");
            }
            var rules = new CompositePropertySource(RULES_SOURCE);
            sources.forEach(rules::addPropertySource);
            return rules;
        } catch (IOException e) {
            throw new IllegalStateException("The simulator rules file " + file + " could not be read", e);
        }
    }
}
