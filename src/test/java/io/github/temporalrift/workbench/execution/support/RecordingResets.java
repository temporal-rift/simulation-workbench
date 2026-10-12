package io.github.temporalrift.workbench.execution.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneResets;

/** A reset hook that records every call and can be told to refuse the next ones. */
public class RecordingResets implements LaneResets {

    private final List<Call> calls = new ArrayList<>();
    private final AtomicInteger refusals = new AtomicInteger();

    /** One call of the hook and whether the deployment answered that the lane is clean. */
    public record Call(String laneId, UUID caseKey, UUID idempotencyKey, boolean succeeded) {}

    /** Answers the next {@code count} calls with {@code 503}. */
    public void refuseNext(int count) {
        refusals.set(count);
    }

    @Override
    public synchronized void reset(String laneId, UUID caseKey, UUID idempotencyKey) {
        var refused = refusals.getAndUpdate(left -> Math.max(0, left - 1)) > 0;
        calls.add(new Call(laneId, caseKey, idempotencyKey, !refused));
        if (refused) {
            throw new IllegalStateException("status 503");
        }
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized void clear() {
        calls.clear();
    }

    public synchronized List<Call> callsFor(String laneId) {
        return calls.stream().filter(call -> call.laneId().equals(laneId)).toList();
    }
}
