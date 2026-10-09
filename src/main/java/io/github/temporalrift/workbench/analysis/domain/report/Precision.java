package io.github.temporalrift.workbench.analysis.domain.report;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Reported numbers are rounded half-even to 6 decimal places, so equal inputs always print equal text. */
public final class Precision {

    private static final int SCALE = 6;

    private Precision() {}

    public static Double round(double value) {
        return Double.isNaN(value)
                ? null
                : BigDecimal.valueOf(value)
                        .setScale(SCALE, RoundingMode.HALF_EVEN)
                        .stripTrailingZeros()
                        .doubleValue();
    }
}
