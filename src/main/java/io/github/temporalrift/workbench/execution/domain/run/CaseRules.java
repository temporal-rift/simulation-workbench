package io.github.temporalrift.workbench.execution.domain.run;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The effective rules a variant stands for, as the values a rules document holds (numbers, booleans, strings and lists
 * of those), with the digest of their canonical form.
 *
 * @param properties every {@code game.rules.*} property by its full key
 * @param digest the effective rules digest of the variant
 */
public record CaseRules(SortedMap<String, Object> properties, String digest) {

    public CaseRules {
        properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties));
        if (!BundleDigests.isDigest(digest)) {
            throw new IllegalArgumentException("digest must be 64 lowercase hex characters: " + digest);
        }
    }
}
