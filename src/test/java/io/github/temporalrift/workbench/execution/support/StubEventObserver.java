package io.github.temporalrift.workbench.execution.support;

import java.util.Map;
import java.util.Optional;

import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver;

/** An observer whose progress a test controls: whether it has caught up and which ending it has seen. */
public class StubEventObserver implements GameEventObserver {

    private volatile boolean caughtUp = true;
    private volatile boolean readSideApplied = true;
    private volatile Map<EvidenceLedger.SourcePartition, Long> endOffsets = Map.of();
    private volatile GameEnded gameEnded;
    private volatile boolean closed;

    public StubEventObserver caughtUp(boolean value) {
        this.caughtUp = value;
        return this;
    }

    public StubEventObserver readSideApplied(boolean value) {
        this.readSideApplied = value;
        return this;
    }

    public StubEventObserver endOffsets(Map<EvidenceLedger.SourcePartition, Long> value) {
        this.endOffsets = Map.copyOf(value);
        return this;
    }

    public StubEventObserver gameEnded(GameEnded value) {
        this.gameEnded = value;
        return this;
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public boolean drain() {
        return caughtUp;
    }

    @Override
    public Map<EvidenceLedger.SourcePartition, Long> endOffsets() {
        return endOffsets;
    }

    @Override
    public boolean readSideApplied() {
        return readSideApplied;
    }

    @Override
    public Optional<GameEnded> gameEnded() {
        return Optional.ofNullable(gameEnded);
    }

    @Override
    public void close() {
        closed = true;
    }
}
