package io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs work in a transaction of its own, whatever transaction the caller is in. */
@Component
public class NewTransaction {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void run(Runnable work) {
        work.run();
    }
}
