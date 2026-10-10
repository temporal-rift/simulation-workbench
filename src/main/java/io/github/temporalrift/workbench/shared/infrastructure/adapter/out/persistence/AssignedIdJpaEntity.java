package io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Base of entities whose identifier is assigned by the application. Without it Spring Data cannot tell a new
 * entity from a detached one and reads the row first, which would cost a query per inserted row.
 */
@MappedSuperclass
public abstract class AssignedIdJpaEntity<I> implements Persistable<I> {

    @Transient
    private boolean fresh = true;

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        fresh = false;
    }
}
