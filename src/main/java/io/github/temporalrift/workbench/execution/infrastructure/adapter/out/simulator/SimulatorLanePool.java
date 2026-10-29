package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.LaneProfile;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.HttpLaneContexts;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneContexts;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneRoster;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneWaits;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ResetScheduler;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.RosterBackedLaneProvider;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.Sleeper;

/**
 * The simulator lane: slots that each host the three services in this JVM under profile {@code in-memory}. A slot
 * plays one case at a time, and is made clean after each case by closing the case's execution context on its services,
 * as a real lane is, so slots are the unit of parallelism. A slot's reset, after a failed close or a service that still
 * held an earlier case, restarts its services. All slots host the same builds under the same rules and so serve one
 * bundle.
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
            LaneWaits waits,
            BundleDigests bundle,
            BundleProvenance provenance) {}

    /**
     * What the deployment declares about the slots.
     *
     * @param rulesFile the {@code game.rules.*} properties the services play under
     * @param workDirectory where the jars are extracted
     * @param botPlayerIds the player id of each bot identity every slot mints, in seat order
     * @param idleTimeout how long a slot's services may host no case before they stop; zero keeps them running
     */
    public record Deployment(
            int slots,
            Path gameServiceJar,
            Path timelineServiceJar,
            Path readServiceJar,
            Path rulesFile,
            Path workDirectory,
            List<UUID> botPlayerIds,
            Duration idleTimeout,
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
            LaneWaits waits,
            ResetScheduler scheduler) {}

    private SimulatorLanePool(
            LaneRoster roster,
            Map<String, SimulatorSlot> slots,
            Hosting hosting,
            List<ServedBundle> servedBundles,
            Map<String, LaneProfile> profiles) {
        super(roster, servedBundles, profiles);
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
        slotIds.forEach(id -> slots.put(
                id,
                new SimulatorSlot(
                        id,
                        build,
                        deployment.botPlayerIds(),
                        with.clock(),
                        deployment.idleTimeout(),
                        with.scheduler())));
        var fit = LaneFit.of(deployment.bundle().contentDigest(), provenance);
        var roster = new LaneRoster(
                slotIds.stream().map(id -> new LaneRoster.Member(id, fit, true)).toList(),
                new SlotContexts(slots, with.clients(), deployment.bundle(), provenance),
                (laneId, _, _) -> slots.get(laneId).restart(),
                with.scheduler());
        var hosting = new Hosting(
                with.clients(),
                with.commands(),
                with.evidence(),
                with.objectMapper(),
                with.clock(),
                with.barrier(),
                with.sleeper(),
                with.waits(),
                deployment.bundle(),
                provenance);
        var served = ServedBundle.counted(slotIds.stream()
                .map(_ -> ServedBundle.ofLane(deployment.bundle(), provenance))
                .toList());
        var profile = new LaneProfile(
                fit, deployment.bundle(), provenance, LaneProfile.Kind.IN_PROCESS, deployment.botPlayerIds());
        var profiles = new LinkedHashMap<String, LaneProfile>();
        slotIds.forEach(id -> profiles.put(id, profile));
        return new SimulatorLanePool(roster, slots, hosting, served, profiles);
    }

    /**
     * Closes contexts through the services' own control interface, exactly as on a real lane. A slot that runs no
     * services holds no context, so there is nothing to close.
     */
    private record SlotContexts(
            Map<String, SimulatorSlot> slots, ApiClients clients, BundleDigests bundle, BundleProvenance provenance)
            implements LaneContexts {

        @Override
        public void close(String laneId, UUID caseKey, Set<LaneService> services) {
            var slot = slots.get(laneId);
            slot.running().ifPresent(runtime -> contexts(slot, runtime).close(laneId, caseKey, services));
        }

        @Override
        public void closeAnyOpen(String laneId) {
            var slot = slots.get(laneId);
            slot.running().ifPresent(runtime -> contexts(slot, runtime).closeAnyOpen(laneId));
        }

        private HttpLaneContexts contexts(SimulatorSlot slot, SlotRuntime runtime) {
            return new HttpLaneContexts(List.of(endpoints(slot, runtime, bundle, provenance)), clients);
        }
    }

    /** Where a slot's running services answer, and who plays and controls them. */
    static LaneEndpoints endpoints(
            SimulatorSlot slot, SlotRuntime runtime, BundleDigests bundle, BundleProvenance provenance) {
        return new LaneEndpoints(
                slot.id(),
                runtime.gameServiceUrl(),
                runtime.timelineServiceUrl(),
                runtime.readServiceUrl(),
                runtime.operatorToken(),
                bundle,
                provenance,
                SlotEventRouter.GAME_EVENTS,
                SlotEventRouter.TIMELINE_EVENTS,
                null,
                null,
                runtime.bots());
    }

    /** The slot behind a lane. */
    SimulatorSlot slot(String laneId) {
        return slots.get(laneId);
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
