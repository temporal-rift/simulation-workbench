package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The issuer of one slot: it mints the bearer tokens of the bots and of the operator, and the slot's services trust
 * exactly its signature. A token carries a stable subject and no random claims; the key lives only in this process.
 */
final class SimulatorIssuer {

    static final String OPERATOR_SUBJECT = "simulation-operator";

    private static final String CONTROL_SCOPE = "simulation:control";
    private static final String ISSUER = "simulation-workbench";
    private static final Duration VALIDITY = Duration.ofDays(7);
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final JwtEncoder encoder;
    private final Clock clock;

    SimulatorIssuer(Clock clock) {
        var secret = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(secret);
        this.key = new SecretKeySpec(secret, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.clock = clock;
    }

    /** The decoder the slot's services use to authenticate every request. */
    JwtDecoder decoder() {
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /** A bot's bearer token: its player id is the subject, as for any player. */
    String bot(UUID playerId) {
        return mint(JwtClaimsSet.builder().subject(playerId.toString()));
    }

    /** The operator's token, which holds the simulation control scope. */
    String operator() {
        return mint(JwtClaimsSet.builder().subject(OPERATOR_SUBJECT).claim("scope", CONTROL_SCOPE));
    }

    private String mint(JwtClaimsSet.Builder claims) {
        var now = clock.instant();
        var set = claims.issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(VALIDITY))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, set)).getTokenValue();
    }
}
