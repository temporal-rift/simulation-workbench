package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.regex.Pattern;

import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;

/**
 * Reads back the window keys the policy package assigns ({@code era1/hand-selection},
 * {@code era1/declaration}, {@code era1/round2/action}, {@code era1/paradox-resolution}) into the window,
 * era and round the participant projection records accepted submissions under.
 */
final class WindowKeys {

    private static final Pattern KEY = Pattern.compile("era(\\d+)/(?:round(\\d+)/)?([a-z-]+)");

    private WindowKeys() {}

    /** The coordinates an accepted submission of the window is recorded under. */
    record Ref(String window, int era, Integer round) {}

    static Ref parse(String key) {
        var matcher = KEY.matcher(key);
        if (!matcher.matches()) {
            throw unknown(key);
        }
        var era = Integer.parseInt(matcher.group(1));
        var round = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
        var window = switch (matcher.group(3)) {
            case "hand-selection" -> "HAND_SELECTION";
            case "declaration" -> "DECLARATION";
            case "action" -> "ACTION";
            case "paradox-resolution" -> "PARADOX_RESOLUTION";
            default -> throw unknown(key);
        };
        return new Ref(window, era, round);
    }

    private static AttemptFailedException unknown(String key) {
        return new AttemptFailedException(FailureCode.CONTRACT_MISMATCH, "Unrecognized decision window " + key);
    }
}
