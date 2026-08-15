package dev.ronin.gwentmousebridge;

/** Coalesces Linux input events into one mouse update per EV_SYN/SYN_REPORT frame. */
final class GetEventParser {
    private static final int EV_SYN = 0x00;
    private static final int EV_KEY = 0x01;
    private static final int EV_REL = 0x02;
    private static final int SYN_REPORT = 0x00;
    private static final int REL_X = 0x00;
    private static final int REL_Y = 0x01;
    private static final int BTN_LEFT = 0x110; // BTN_MOUSE is the canonical alias.

    static final class Frame {
        final int dx;
        final int dy;
        final Boolean leftButtonDown;

        Frame(int dx, int dy, Boolean leftButtonDown) {
            this.dx = dx;
            this.dy = dy;
            this.leftButtonDown = leftButtonDown;
        }
    }

    private int pendingDx;
    private int pendingDy;
    private Boolean pendingLeftButtonDown;

    Frame accept(String rawLine) {
        if (rawLine == null) return null;
        String line = rawLine.trim();
        if (line.isEmpty()) return null;

        String[] tokens = line.split("\\s+");
        int relIndex = indexOf(tokens, "EV_REL");
        if (relIndex >= 0 && relIndex + 2 < tokens.length) {
            Integer value = parseHex(tokens[relIndex + 2]);
            if (value == null) return null;
            if ("REL_X".equals(tokens[relIndex + 1])) pendingDx += value;
            else if ("REL_Y".equals(tokens[relIndex + 1])) pendingDy += value;
            return null;
        }

        int keyIndex = indexOf(tokens, "EV_KEY");
        if (keyIndex >= 0 && keyIndex + 2 < tokens.length) {
            String key = tokens[keyIndex + 1];
            if ("BTN_MOUSE".equals(key) || "BTN_LEFT".equals(key)) {
                pendingLeftButtonDown = parseButtonValue(tokens[keyIndex + 2]);
            }
            return null;
        }

        int synIndex = indexOf(tokens, "EV_SYN");
        if (synIndex >= 0 && synIndex + 1 < tokens.length) {
            if ("SYN_REPORT".equals(tokens[synIndex + 1])) return flush();
            if ("SYN_DROPPED".equals(tokens[synIndex + 1])) clearPending();
            return null;
        }

        // getevent may omit labels. Its final three fields are numeric type, code and value.
        if (tokens.length < 3) return null;
        Integer type = parseHex(tokens[tokens.length - 3]);
        Integer code = parseHex(tokens[tokens.length - 2]);
        Integer value = parseHex(tokens[tokens.length - 1]);
        if (type == null || code == null || value == null) return null;

        if (type == EV_REL) {
            if (code == REL_X) pendingDx += value;
            else if (code == REL_Y) pendingDy += value;
        } else if (type == EV_KEY && code == BTN_LEFT) {
            pendingLeftButtonDown = value != 0;
        } else if (type == EV_SYN) {
            if (code == SYN_REPORT) return flush();
            clearPending();
        }
        return null;
    }

    private Frame flush() {
        if (pendingDx == 0 && pendingDy == 0 && pendingLeftButtonDown == null) return null;
        Frame frame = new Frame(pendingDx, pendingDy, pendingLeftButtonDown);
        clearPending();
        return frame;
    }

    private void clearPending() {
        pendingDx = 0;
        pendingDy = 0;
        pendingLeftButtonDown = null;
    }

    private static int indexOf(String[] tokens, String expected) {
        for (int i = 0; i < tokens.length; i++) {
            if (expected.equals(tokens[i])) return i;
        }
        return -1;
    }

    private static Boolean parseButtonValue(String token) {
        if ("DOWN".equalsIgnoreCase(token)) return true;
        if ("UP".equalsIgnoreCase(token)) return false;
        Integer value = parseHex(token);
        return value == null ? null : value != 0;
    }

    /** Parsing through long preserves signed 32-bit REL values such as fffffffe -> -2. */
    private static Integer parseHex(String token) {
        try {
            String value = token;
            if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
            return (int) Long.parseLong(value, 16);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
