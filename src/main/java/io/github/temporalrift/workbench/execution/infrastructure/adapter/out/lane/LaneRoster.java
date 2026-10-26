package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;

/**
 * Which lanes may be offered right now. A lane hosts one case at a time; when the case ends, its execution context is
 * closed on the lane's services before the lane is offered again. A lane whose close failed is recovered through its
 * deployment's reset hook when it has one, otherwise the close is retried. Every lane is unavailable until
 * {@link #prepare prepared} on start.
 */
public class LaneRoster {

    private static final Logger LOG = LoggerFactory.getLogger(LaneRoster.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(1);
    private static final Duration LAST_RETRY = Duration.ofMinutes(1);

    private final Map<String, Seat> seats;
    private final LaneContexts contexts;
    private final LaneResets resets;
    private final ResetScheduler scheduler;

    /** A lane, what it fits, and whether its deployment can reset it after a failed close. */
    public record Member(String id, LaneFit fit, boolean resettable) {}

    private enum Availability {
        FREE,
        BUSY,
        RELEASING,
        UNPREPARED
    }

    private static final class Seat {
        private final Member member;
        private Availability availability = Availability.UNPREPARED;

        private Seat(Member member) {
            this.member = member;
        }
    }

    /** What has to be undone before a lane is offered again; no case key means whichever context is open. */
    private record Release(Member lane, UUID caseKey, Set<LaneService> services, UUID idempotencyKey) {}

    public LaneRoster(List<Member> members, LaneContexts contexts, LaneResets resets, ResetScheduler scheduler) {
        this.seats = members.stream()
                .collect(Collectors.toMap(Member::id, Seat::new, (first, _) -> first, LinkedHashMap::new));
        this.contexts = contexts;
        this.resets = resets;
        this.scheduler = scheduler;
    }

    /** Takes a free lane that fits, preferring {@code preferredLaneId}. */
    public synchronized Optional<Tenancy> take(LaneFit fit, String preferredLaneId) {
        var offered = seats.values().stream()
                .filter(seat -> seat.availability == Availability.FREE)
                .filter(seat -> seat.member.fit().equals(fit))
                .toList();
        return offered.stream()
                .filter(seat -> seat.member.id().equals(preferredLaneId))
                .findFirst()
                .or(() -> offered.stream().findFirst())
                .map(this::occupy);
    }

    /** Takes exactly this lane when it is free and fits. */
    public synchronized Optional<Tenancy> takeExactly(String laneId, LaneFit fit) {
        return Optional.ofNullable(seats.get(laneId))
                .filter(seat -> seat.availability == Availability.FREE)
                .filter(seat -> seat.member.fit().equals(fit))
                .map(this::occupy);
    }

    public synchronized Map<String, LaneFit> free() {
        return seats.values().stream()
                .filter(seat -> seat.availability == Availability.FREE)
                .collect(Collectors.toUnmodifiableMap(seat -> seat.member.id(), seat -> seat.member.fit()));
    }

    /** Closes whatever context a previous process left open on each unprepared lane, then offers the lane. */
    public void prepare() {
        List<Seat> unprepared;
        synchronized (this) {
            unprepared = seats.values().stream()
                    .filter(seat -> seat.availability == Availability.UNPREPARED)
                    .toList();
            unprepared.forEach(seat -> seat.availability = Availability.RELEASING);
        }
        unprepared.forEach(seat -> release(new Release(seat.member, null, controlled(), UUID.randomUUID())));
    }

    private static Set<LaneService> controlled() {
        return EnumSet.of(LaneService.GAME, LaneService.TIMELINE);
    }

    private Tenancy occupy(Seat seat) {
        seat.availability = Availability.BUSY;
        return new Tenancy(seat);
    }

    /** Closes the contexts; a failure hands the lane to the retrying recovery. */
    private void release(Release release) {
        try {
            closeContexts(release);
            markFree(release.lane());
        } catch (RuntimeException e) {
            LOG.warn("Closing the context of lane {} failed: {}", release.lane().id(), describe(e));
            scheduler.schedule(() -> recover(release, 0), Duration.ZERO);
        }
    }

    /**
     * One try to make the lane clean after a failed close: the deployment's reset hook, or the close again when the
     * lane has none. Whatever ends it short of a clean lane, an error included, schedules the next.
     */
    private void recover(Release release, int failures) {
        var cleaned = false;
        try {
            if (release.lane().resettable()) {
                resets.reset(release.lane().id(), release.caseKey(), release.idempotencyKey());
            } else {
                closeContexts(release);
            }
            cleaned = true;
        } catch (RuntimeException e) {
            LOG.warn("Recovery of lane {} failed: {}", release.lane().id(), describe(e));
        } finally {
            if (cleaned) {
                markFree(release.lane());
            } else {
                var delay = retryDelay(failures);
                LOG.warn(
                        "Lane {} is not clean yet; retrying in {}",
                        release.lane().id(),
                        delay);
                scheduler.schedule(() -> recover(release, failures + 1), delay);
            }
        }
    }

    private void closeContexts(Release release) {
        if (release.caseKey() == null) {
            contexts.closeAnyOpen(release.lane().id());
        } else {
            contexts.close(release.lane().id(), release.caseKey(), release.services());
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

    /** One acquisition of a lane: the case it hosted and the services it was opened on, released once on return. */
    public final class Tenancy {

        private final Seat seat;
        private final AtomicBoolean returned = new AtomicBoolean();
        private final Set<LaneService> opened = EnumSet.noneOf(LaneService.class);
        private volatile UUID caseKey;

        private Tenancy(Seat seat) {
            this.seat = seat;
        }

        public String laneId() {
            return seat.member.id();
        }

        /** Records the case the lane is about to host; a context sent from here on has to be closed on return. */
        public void host(CaseLane.CaseContext context) {
            caseKey = context.caseKey();
        }

        /** Records that a service may hold the hosted case's context. */
        public synchronized void opened(LaneService service) {
            opened.add(service);
        }

        /** Records that a service definitely refused the hosted case's context. */
        public synchronized void refused(LaneService service) {
            opened.remove(service);
        }

        /** Returns the lane, closing the contexts it opened; later calls do nothing. */
        public void giveBack() {
            if (!returned.compareAndSet(false, true)) {
                return;
            }
            Set<LaneService> services;
            synchronized (this) {
                services = Set.copyOf(opened);
            }
            if (caseKey == null || services.isEmpty()) {
                markFree(seat.member);
                return;
            }
            synchronized (LaneRoster.this) {
                seat.availability = Availability.RELEASING;
            }
            release(new Release(seat.member, caseKey, services, UUID.randomUUID()));
        }
    }
}
