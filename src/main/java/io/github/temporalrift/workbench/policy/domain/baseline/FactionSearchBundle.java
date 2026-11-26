package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.Map;

import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * {@code faction-search}: {@code faction-spread} whose preferences, leans, thresholds and spread behaviors are
 * {@link SearchParameters}. With none named it decides exactly as {@code faction-spread} does. Its artifact digest
 * hashes the id, version, behavior revision and the canonical non-default parameters, so another parameter set is
 * another digest.
 */
public final class FactionSearchBundle {

    public static final String ID = "faction-search";
    public static final String VERSION = BaselinePolicies.VERSION;

    /** Bump whenever what the bundle decides for a given parameter set changes. */
    static final String REVISION = "1";

    private static final String DESCRIPTION = "faction-spread with its preferences, leans, thresholds and spread"
            + " behaviors as parameters, so a search can find strategies the standard bots never play; at its defaults"
            + " it decides as faction-spread does.";

    private FactionSearchBundle() {}

    /**
     * The bundle playing the named parameters.
     *
     * @throws SearchParameters.Refused when a parameter is unknown, out of its range or off its step
     */
    public static PolicyBundle of(Map<String, Integer> named) {
        return of(SearchParameters.of(named));
    }

    public static PolicyBundle of(SearchParameters parameters) {
        return new PolicyBundle(
                ID, VERSION, REVISION, digestOf(parameters), DESCRIPTION, new FactionSpreadPolicy(parameters));
    }

    /** The bundle at its default parameters. */
    public static PolicyBundle defaults() {
        return of(SearchParameters.DEFAULTS);
    }

    /** The digest of the bundle playing these parameters. */
    public static String digestOf(SearchParameters parameters) {
        return Sha256.hexUtf8(ID + "|" + VERSION + "|" + REVISION + "|" + parameters.canonical());
    }
}
