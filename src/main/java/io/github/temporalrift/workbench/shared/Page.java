package io.github.temporalrift.workbench.shared;

import java.util.List;

/** One page of a newest-first or ordered list together with the total number of matches. */
public record Page<T>(List<T> items, long total) {

    public Page {
        items = List.copyOf(items);
    }

    /** The page of {@code all} starting at {@code offset} with at most {@code limit} items. */
    public static <T> Page<T> of(List<T> all, int limit, int offset) {
        int from = Math.min(offset, all.size());
        return new Page<>(all.subList(from, Math.min(all.size(), from + limit)), all.size());
    }
}
