package io.github.makaseloli.ghastfsd.automation;

enum AutopilotPhase {
    CRUISE,
    APPROACH,
    DESCEND,
    ALIGN,
    DOCKED;

    static AutopilotPhase parse(String value) {
        if (value == null || value.isBlank()) {
            return CRUISE;
        }
        try {
            return AutopilotPhase.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return CRUISE;
        }
    }
}
