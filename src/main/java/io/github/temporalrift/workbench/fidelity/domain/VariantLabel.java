package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Locale;

/** The three variants a check plays, in the order it lists them. */
public enum VariantLabel {
    BASELINE,
    LOW,
    HIGH;

    /** The label as the manifest and the contract spell it. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static VariantLabel ofLabel(String label) {
        return valueOf(label.toUpperCase(Locale.ROOT));
    }
}
