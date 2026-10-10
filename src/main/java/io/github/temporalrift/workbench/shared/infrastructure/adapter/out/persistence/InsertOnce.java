package io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Inserts a row that may already exist: the primary key or unique constraint decides a race, and the loser
 * learns it lost. The insert runs in a transaction of its own, because a failed insert would otherwise leave the
 * caller's transaction unusable.
 */
@Component
public class InsertOnce {

    private final NewTransaction transaction;

    public InsertOnce(NewTransaction transaction) {
        this.transaction = transaction;
    }

    /** Runs the insert and returns whether it stored the row. */
    public boolean insert(Runnable insert) {
        try {
            transaction.run(insert);
            return true;
        } catch (DataIntegrityViolationException alreadyThere) {
            return false;
        }
    }
}
