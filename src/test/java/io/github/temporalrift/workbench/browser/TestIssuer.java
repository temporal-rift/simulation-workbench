package io.github.temporalrift.workbench.browser;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * An interactive OIDC issuer for browser tests. Any username signs in, and the username picks the scopes the
 * access token carries, so one service can be exercised as designers with different entitlements.
 */
final class TestIssuer {

    /** Reads and writes, but is not entitled to observer evidence. */
    static final String ANALYST = "analyst";

    /** Everything a designer can be granted. */
    static final String DESIGNER = "designer";

    /** May look but not change anything. */
    static final String READER = "reader";

    private static final String CONFIG = """
            {"interactiveLogin":true,
             "tokenCallbacks":[{"issuerId":"default","requestMappings":[
              {"requestParam":"subject","match":"designer","claims":{"scope":
               "simulation:read simulation:write simulation:observe"}},
              {"requestParam":"subject","match":"analyst","claims":{"scope":"simulation:read simulation:write"}},
              {"requestParam":"subject","match":"reader","claims":{"scope":"simulation:read"}}]}]}
            """;

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(
                    "ghcr.io/navikt/mock-oauth2-server:6.0.3")
            .withExposedPorts(8080)
            .withEnv("JSON_CONFIG", CONFIG.replaceAll("\\s+", " "))
            .waitingFor(
                    Wait.forHttp("/default/.well-known/openid-configuration").forPort(8080));

    static {
        CONTAINER.start();
    }

    private TestIssuer() {}

    /** The issuer as both the service and the browser reach it, which is also the `iss` of its tokens. */
    static String issuerUri() {
        return "http://%s:%d/default".formatted(CONTAINER.getHost(), CONTAINER.getMappedPort(8080));
    }
}
