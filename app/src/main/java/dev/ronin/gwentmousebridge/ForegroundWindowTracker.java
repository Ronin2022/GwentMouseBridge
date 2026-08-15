package dev.ronin.gwentmousebridge;

import java.util.List;

/** Distinguishes a focused game window from non-focusable overlays such as heads-up notices. */
final class ForegroundWindowTracker {
    static final int UNDEFINED_WINDOW_ID = -1;

    enum Decision {
        GWENT,
        OTHER,
        UNKNOWN
    }

    static final class WindowSnapshot {
        final int id;
        final boolean focused;
        final String packageName;

        WindowSnapshot(int id, boolean focused, String packageName) {
            this.id = id;
            this.focused = focused;
            this.packageName = packageName;
        }
    }

    private final String targetPackage;
    private int targetWindowId = UNDEFINED_WINDOW_ID;

    ForegroundWindowTracker(String targetPackage) {
        this.targetPackage = targetPackage;
    }

    void rememberTargetWindow(int windowId) {
        if (windowId != UNDEFINED_WINDOW_ID) targetWindowId = windowId;
    }

    Decision evaluate(List<WindowSnapshot> windows) {
        if (windows == null || windows.isEmpty()) return Decision.UNKNOWN;
        boolean targetFocused = false;
        boolean otherFocused = false;
        for (WindowSnapshot window : windows) {
            if (window == null || !window.focused) continue;
            if (window.id == targetWindowId || targetPackage.equals(window.packageName)) {
                rememberTargetWindow(window.id);
                targetFocused = true;
            } else otherFocused = true;
        }
        if (otherFocused) return Decision.OTHER;
        if (targetFocused) return Decision.GWENT;
        return Decision.UNKNOWN;
    }
}
