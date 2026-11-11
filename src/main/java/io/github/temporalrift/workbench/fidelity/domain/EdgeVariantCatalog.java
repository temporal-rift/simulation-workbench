package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The edge variants of every parameter space the fidelity check supports. A new check runs on the {@link #newest()}
 * space; a stored check keeps the space it was accepted with.
 */
public final class EdgeVariantCatalog {

    private final SortedMap<Integer, EdgeVariants> bySpace;

    public EdgeVariantCatalog(Collection<EdgeVariants> variants) {
        var spaces = new TreeMap<Integer, EdgeVariants>();
        variants.forEach(item -> {
            if (spaces.put(item.parameterSpaceVersion(), item) != null) {
                throw new IllegalArgumentException(
                        "Edge variants are listed twice for parameter space " + item.parameterSpaceVersion());
            }
        });
        if (spaces.isEmpty()) {
            throw new IllegalArgumentException("At least one parameter space needs edge variants");
        }
        this.bySpace = Collections.unmodifiableSortedMap(spaces);
    }

    /** The edge variants of the newest parameter space, which new checks use. */
    public EdgeVariants newest() {
        return bySpace.get(bySpace.lastKey());
    }

    /** The edge variants of one parameter space. */
    public EdgeVariants forSpace(int parameterSpaceVersion) {
        var variants = bySpace.get(parameterSpaceVersion);
        if (variants == null) {
            throw new NoSuchElementException("No edge variants for parameter space " + parameterSpaceVersion);
        }
        return variants;
    }

    /** The parameter spaces that have edge variants, oldest first. */
    public Collection<EdgeVariants> all() {
        return bySpace.values();
    }
}
