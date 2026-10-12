package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;

/**
 * Which lanes may be offered right now. A lane hosts one case at a time; a resettable lane that hosted a case is reset
 * through its deployment before it is offered again, unless its attempt was interrupted: then it keeps its game for
 * that case's recovering attempt. Resettable lanes are unavailable until {@link #prepare prepared} on start.
 */
public class LaneRoster {

    private static final Logger LOG = LoggerFactory.getLogger(LaneRoster.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(1);
    private static final Duration LAST_RETRY = Duration.ofMinutes(1);

    private final Map<String, Seat> seats;
    private final LaneResets resets;
    private final ResetScheduler scheduler;

    /** A lane, the bundle it serves, and whether its deployment can reset it. */
    public record Member(String id, BundleDigests bundle, boolean resettable) {}

    private enum Availability {
        FREE,
        BUSY,
        RESETTING,
        HELD,
        UNPREPARED
    }

    private static final class Seat {
        private final Member member;
        private Availability availability;
        private LaneProvider.Hold hold;

        private Seat(Member member) {
            this.member = member;
            this.availability = member.resettable() ? Availability.UNPREPARED : Availability.FREE;
        }
    }

    public LaneRoster(List<Member> members, LaneResets resets, ResetScheduler scheduler) {
        this.seats = members.stream()
                .collect(Collectors.toMap(Member::id, Seat::new, (first, _) -> first, LinkedHashMap::new));
        this.resets = resets;
        this.scheduler = scheduler;
    }

    /**
     * Takes a free lane serving {@code bundle}, or one holding the game of {@code caseId}, preferring
     * {@code preferredLaneId}, then a lane held for the case.
     */
    public synchronized Optional<Tenancy> take(UUID caseId, BundleDigests bundle, String preferredLaneId) {
        var offered = seats.values().stream()
                .filter(seat -> seat.member.bundle().equals(bundle))
                .filter(seat -> seat.availability == Availability.FREE || heldFor(seat, caseId))
                .toList();
        return offered.stream()
                .filter(seat -> seat.member.id().equals(preferredLaneId))
                .findFirst()
                .or(() -> offered.stream().filter(seat -> heldFor(seat, caseId)).findFirst())
                .or(() -> offered.stream().findFirst())
                .map(this::occupy);
    }

    /** Takes exactly this lane when it is free and serves {@code bundle}. */
    public synchronized Optional<Tenancy> takeExactly(String laneId, BundleDigests bundle) {
        return Optional.ofNullable(seats.get(laneId))
                .filter(seat -> seat.availability == Availability.FREE)
                .filter(seat -> seat.member.bundle().equals(bundle))
                .map(this::occupy);
    }

    public Set<BundleDigests> servedBundles() {
        return seats.values().stream().map(seat -> seat.member.bundle()).collect(Collectors.toUnmodifiableSet());
    }

    public synchronized Map<String, BundleDigests> free() {
        return seats.values().stream()
                .filter(seat -> seat.availability == Availability.FREE)
                .collect(Collectors.toUnmodifiableMap(seat -> seat.member.id(), seat -> seat.member.bundle()));
    }

    public synchronized Set<UUID> heldCases() {
        return seats.values().stream()
                .filter(seat -> seat.availability == Availability.HELD)
                .map(seat -> seat.hold.caseId())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Holds every unprepared lane an interrupted attempt left its game on, and resets the other unprepared ones. */
    public void prepare(Collection<LaneProvider.Hold> interrupted) {
        var holds = interrupted.stream()
                .collect(Collectors.toMap(LaneProvider.Hold::laneId, Function.identity(), (first, _) -> first));
        List<Seat> toReset;
        synchronized (this) {
            var unprepared = seats.values().stream()
                    .filter(seat -> seat.availability == Availability.UNPREPARED)
                    .toList();
            unprepared.stream()
                    .filter(seat -> holds.containsKey(seat.member.id()))
                    .forEach(seat -> hold(seat, holds.get(seat.member.id())));
            toReset = unprepared.stream()
                    .filter(seat -> !holds.containsKey(seat.member.id()))
                    .toList();
            toReset.forEach(seat -> seat.availability = Availability.RESETTING);
        }
        toReset.forEach(seat -> reset(seat.member, null));
    }

    /** Resets the lanes held for a case that no longer needs its game. */
    public void releaseHeld(UUID caseId) {
        List<LaneProvider.Hold> released;
        synchronized (this) {
            var held = seats.values().stream()
                    .filter(seat -> heldFor(seat, caseId))
                    .toList();
            held.forEach(seat -> seat.availability = Availability.RESETTING);
            released = held.stream().map(seat -> seat.hold).toList();
        }
        released.forEach(hold -> reset(seats.get(hold.laneId()).member, hold.caseKey()));
    }

    private Tenancy occupy(Seat seat) {
        seat.availability = Availability.BUSY;
        seat.hold = null;
        return new Tenancy(seat);
    }

    private static boolean heldFor(Seat seat, UUID caseId) {
        return seat.availability == Availability.HELD && seat.hold.caseId().equals(caseId);
    }

    private static void hold(Seat seat, LaneProvider.Hold hold) {
        seat.availability = Availability.HELD;
        seat.hold = hold;
    }

    private void reset(Member lane, UUID caseKey) {
        var idempotencyKey = UUID.randomUUID();
        scheduler.schedule(() -> tryReset(lane, caseKey, idempotencyKey, 0), Duration.ZERO);
    }

    /** One try of a release; whatever ends it short of a clean lane, an error included, schedules the next. */
    private void tryReset(Member lane, UUID caseKey, UUID idempotencyKey, int failures) {
        var cleaned = false;
        try {
            resets.reset(lane.id(), caseKey, idempotencyKey);
            cleaned = true;
        } catch (RuntimeException e) {
            LOG.warn("Reset of lane {} failed: {}", lane.id(), describe(e));
        } finally {
            if (cleaned) {
                markFree(lane);
            } else {
                var delay = retryDelay(failures);
                LOG.warn("Reset of lane {} did not complete; retrying in {}", lane.id(), delay);
                scheduler.schedule(() -> tryReset(lane, caseKey, idempotencyKey, failures + 1), delay);
            }
        }
    }

    private synchronized void markFree(Member lane) {
        seats.get(lane.id()).availability = Availability.FREE;
    }

    private static Duration retryDelay(int failures) {
        var delay = FIRST_RETRY.multipliedBy(1L << Math.min(failures, 6));
        return delay.compareTo(LAST_RETRY) > 0 ? LAST_RETRY : delay;
    }

    private static String describe(RuntimeException e) {
        var message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }

    /** One acquisition of a lane: what it hosted and whether its game must be kept, reported once on return. */
    public final class Tenancy {

        private final Seat seat;
        private final AtomicBoolean returned = new AtomicBoolean();
        private final AtomicReference<CaseLane.CaseContext> hosted = new AtomicReference<>();
        private final AtomicBoolean retained = new AtomicBoolean();

        private Tenancy(Seat seat) {
            this.seat = seat;
        }

        public String laneId() {
            return seat.member.id();
        }

        /** Records the case the lane is about to be configured for; from here on it needs a reset to be clean. */
        public void host(CaseLane.CaseContext context) {
            hosted.set(context);
        }

        /** Keeps the hosted game on return, for the case's recovering attempt. */
        public void retainForRecovery() {
            retained.set(true);
        }

        /** Returns the lane; later calls do nothing. */
        public void giveBack() {
            if (!returned.compareAndSet(false, true)) {
                return;
            }
            var context = hosted.get();
            synchronized (LaneRoster.this) {
                if (!seat.member.resettable() || context == null) {
                    seat.availability = Availability.FREE;
                    return;
                }
                if (retained.get()) {
                    hold(seat, new LaneProvider.Hold(seat.member.id(), context.caseId(), context.caseKey()));
                    return;
                }
                seat.availability = Availability.RESETTING;
            }
            reset(seat.member, context.caseKey());
        }
    }
}
