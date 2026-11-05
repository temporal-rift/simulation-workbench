package io.github.temporalrift.workbench.search.infrastructure.adapter.out.defaults;

import java.security.SecureRandom;

import io.github.temporalrift.workbench.search.domain.port.out.MasterSeeds;

/** Master seeds drawn uniformly from the whole unsigned 64-bit range. */
public class RandomMasterSeeds implements MasterSeeds {

    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        return Long.toUnsignedString(random.nextLong());
    }
}
