package io.github.temporalrift.workbench.execution.support;

import java.util.Optional;

import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver;

/** An observer whose progress a test controls: whether it has caught up and which ending it has seen. */
public class StubEventObserver implements GameEventObserver {

    private volatile boolean caughtUp = true;
    private volatile GameEnded gameEnded;
    private volatile boolean closed;

    public StubEventObserver caughtUp(boolean value) {
        this.caughtUp = value;
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
    public Optional<GameEnded> gameEnded() {
        return Optional.ofNullable(gameEnded);
    }

    @Override
    public void close() {
        closed = true;
    }
}
