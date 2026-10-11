package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Read-only view of how many of a run's logical cases are in each state. */
@Entity
@Immutable
@Table(name = "run_progress")
class RunProgressJpaEntity {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "pending")
    private long pending;

    @Column(name = "running")
    private long running;

    @Column(name = "succeeded")
    private long succeeded;

    @Column(name = "failed")
    private long failed;

    @Column(name = "cancelled")
    private long cancelled;

    protected RunProgressJpaEntity() {}

    UUID runId() {
        return runId;
    }

    long pending() {
        return pending;
    }

    long running() {
        return running;
    }

    long succeeded() {
        return succeeded;
    }

    long failed() {
        return failed;
    }

    long cancelled() {
        return cancelled;
    }
}
