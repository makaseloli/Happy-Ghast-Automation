package io.github.makaseloli.ghastfsd.automation;

final class AutopilotRouteProgress {
    private AutopilotRouteProgress() {}

    static void advance(AutopilotState state, int routeSize, boolean loop) {
        if (routeSize <= 0) {
            state.index = 0;
        } else if (state.index >= routeSize - 1) {
            state.index = loop ? 0 : routeSize - 1;
        } else {
            state.index++;
        }
        state.waitTicks = 0;
        state.resetNavigation();
    }
}
