package io.github.temporalrift.workbench.shared.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class Sha256Test {

    @Test
    void hexIsTheLowercaseDigest() {
        assertThat(Sha256.hexUtf8("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void aStreamHasTheDigestOfItsBytes() throws IOException {
        var bytes = "abc".getBytes(StandardCharsets.UTF_8);

        assertThat(Sha256.hex(new ByteArrayInputStream(bytes))).isEqualTo(Sha256.hex(bytes));
    }

    @Test
    void partsAreLengthPrefixedSoTheyNeverAlias() {
        assertThat(Sha256.hexOfParts("a", "é"))
                .isEqualTo("1c063bcc31008a2fc30567fa788c6ac017fcf0ff665ee769c4272dd192d5f790");
        assertThat(Sha256.hexOfParts("ab", "c")).isNotEqualTo(Sha256.hexOfParts("a", "bc"));
    }
}
