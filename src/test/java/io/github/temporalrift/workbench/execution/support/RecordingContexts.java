package io.github.temporalrift.workbench.execution.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneContexts;

/** Context closes that are recorded and can be told to fail the next ones. */
public class RecordingContexts implements LaneContexts {

    private final List<Call> calls = new ArrayList<>();
    private final AtomicInteger refusals = new AtomicInteger();

    /** One close; a call that closed whatever was open has no case key. */
    public record Call(String laneId, UUID caseKey, Set<LaneService> services, boolean succeeded) {}

    /** Fails the next {@code count} closes, as a service answering {@code 500} would. */
    public void refuseNext(int count) {
        refusals.set(count);
    }

    @Override
    public synchronized void close(String laneId, UUID caseKey, Set<LaneService> services) {
        register(new Call(laneId, caseKey, Set.copyOf(services), true));
    }

    @Override
    public synchronized void closeAnyOpen(String laneId) {
        register(new Call(laneId, null, Set.of(), true));
    }

    private void register(Call call) {
        var refused = refusals.getAndUpdate(left -> Math.max(0, left - 1)) > 0;
        calls.add(new Call(call.laneId(), call.caseKey(), call.services(), !refused));
        if (refused) {
            throw new IllegalStateException("status 500");
        }
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized List<Call> callsFor(String laneId) {
        return calls.stream().filter(call -> call.laneId().equals(laneId)).toList();
    }

    public synchronized void clear() {
        calls.clear();
        refusals.set(0);
    }
}
