package io.github.temporalrift.workbench.search.infrastructure.adapter.out.defaults;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class RandomMasterSeedsTest {

    private static final BigInteger MAXIMUM = new BigInteger("18446744073709551615");

    private final RandomMasterSeeds seeds = new RandomMasterSeeds();

    @Test
    void everySeedIsADecimalUnsigned64BitInteger() {
        var drawn = Stream.generate(seeds::next).limit(200).toList();

        assertThat(drawn).allSatisfy(seed -> {
            assertThat(seed).matches("0|[1-9][0-9]*");
            assertThat(new BigInteger(seed)).isBetween(BigInteger.ZERO, MAXIMUM);
        });
    }

    @Test
    void successiveSeedsDiffer() {
        assertThat(Stream.generate(seeds::next).limit(200).distinct()).hasSize(200);
    }
}
