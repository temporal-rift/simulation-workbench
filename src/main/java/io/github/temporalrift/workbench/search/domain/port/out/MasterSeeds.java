package io.github.temporalrift.workbench.search.domain.port.out;

/** Where a campaign gets a master seed when its definition names none. */
public interface MasterSeeds {

    /** A new random seed, as a decimal unsigned 64-bit integer. */
    String next();
}
