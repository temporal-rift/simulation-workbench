package io.github.temporalrift.workbench.shared;

import java.util.List;

/** One page of a newest-first or ordered list together with the total number of matches. */
public record Page<T>(List<T> items, long total) {

    public Page {
        items = List.copyOf(items);
    }
}
