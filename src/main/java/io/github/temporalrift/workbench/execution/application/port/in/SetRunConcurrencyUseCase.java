package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

/** Changes how many cases of a run may execute at once, for the cases claimed from now on. */
public interface SetRunConcurrencyUseCase {

    /** @throws io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException when there is no such run */
    void handle(Command command);

    /** {@code concurrency} may be 0, which holds back every case that has not started. */
    record Command(UUID runId, int concurrency) {

        public Command {
            if (concurrency < 0) {
                throw new IllegalArgumentException("concurrency must not be negative");
            }
        }
    }
}
