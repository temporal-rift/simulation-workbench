package io.github.temporalrift.workbench;

import java.util.concurrent.atomic.AtomicInteger;

import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

/** Counts the statements jOOQ executes, so a test can assert how many a write issues. */
public class StatementCounter implements ExecuteListener {

    private final AtomicInteger executed = new AtomicInteger();

    @Override
    public void executeStart(ExecuteContext ctx) {
        executed.incrementAndGet();
    }

    public int executed() {
        return executed.get();
    }
}
