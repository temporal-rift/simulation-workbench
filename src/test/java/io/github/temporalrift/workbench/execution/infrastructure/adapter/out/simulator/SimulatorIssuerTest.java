package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;

class SimulatorIssuerTest {

    private static final UUID PLAYER = new UUID(7, 7);

    private final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    private final SimulatorIssuer issuer = new SimulatorIssuer(clock);

    @Test
    void aBotTokenNamesItsPlayerAsTheSubjectAndHoldsNoControlScope() {
        var jwt = issuer.decoder().decode(issuer.bot(PLAYER));

        assertThat(jwt.getSubject()).isEqualTo(PLAYER.toString());
        assertThat(jwt.getClaimAsString("scope")).isNull();
    }

    @Test
    void theOperatorTokenHoldsTheSimulationControlScope() {
        var jwt = issuer.decoder().decode(issuer.operator());

        assertThat(jwt.getSubject()).isEqualTo(SimulatorIssuer.OPERATOR_SUBJECT);
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("simulation:control");
    }

    @Test
    void theSameIssuerMintsTheSameTokenForTheSamePlayer() {
        assertThat(issuer.bot(PLAYER)).isEqualTo(issuer.bot(PLAYER));
    }

    @Test
    void aTokenOfAnotherIssuerIsNotTrusted() {
        var foreign = new SimulatorIssuer(clock).bot(PLAYER);

        assertThatThrownBy(() -> issuer.decoder().decode(foreign)).isInstanceOf(BadJwtException.class);
    }
}
