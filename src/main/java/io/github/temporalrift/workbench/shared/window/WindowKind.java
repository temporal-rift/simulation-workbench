package io.github.temporalrift.workbench.shared.window;

/** The kind of decision window a seat acts in, in the order a game opens them within an era. */
public enum WindowKind {
    HAND_SELECTION("hand-selection"),
    DECLARATION("declaration"),
    ACTION_ROUND("action"),
    PARADOX_RESOLUTION("paradox-resolution"),
    TERMINAL_READINESS("terminal-readiness");

    private final String slug;

    WindowKind(String slug) {
        this.slug = slug;
    }

    String slug() {
        return slug;
    }
}
