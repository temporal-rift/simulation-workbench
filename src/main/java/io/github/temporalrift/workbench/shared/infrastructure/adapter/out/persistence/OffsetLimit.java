package io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** A page of {@code limit} rows after skipping {@code offset} of them, which a page number cannot express. */
public record OffsetLimit(long offset, int limit) implements Pageable {

    public OffsetLimit {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset must not be negative and limit must be positive");
        }
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetLimit(offset + limit, limit);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetLimit(Math.max(0, offset - limit), limit) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetLimit(0, limit);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetLimit((long) pageNumber * limit, limit);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
