package dev.ronin.gwentmousebridge;

/** Display-sized virtual pointer fed by raw relative mouse deltas. */
final class VirtualCursorState {
    private float x;
    private float y;
    private int width = 1;
    private int height = 1;
    private boolean initialized;

    void updateBounds(int newWidth, int newHeight, boolean recenter) {
        width = Math.max(1, newWidth);
        height = Math.max(1, newHeight);
        if (recenter || !initialized) {
            x = width / 2f;
            y = height / 2f;
            initialized = true;
        } else {
            x = clamp(x, 0f, width - 1f);
            y = clamp(y, 0f, height - 1f);
        }
    }

    void move(int dx, int dy, float sensitivity) {
        x = clamp(x + dx * sensitivity, 0f, width - 1f);
        y = clamp(y + dy * sensitivity, 0f, height - 1f);
    }

    float x() {
        return x;
    }

    float y() {
        return y;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
