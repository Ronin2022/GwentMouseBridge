package dev.ronin.gwentmousebridge;

import java.util.Locale;

/** Parses the labelled output produced by Android's getevent command one SYN frame at a time. */
final class GetEventParser {
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
    private Boolean pendingButton;

    Frame accept(String rawLine) {
        if (rawLine == null) return null;
        String line = rawLine.trim();
        if (line.isEmpty()) return null;

        if (line.contains("EV_REL") && line.contains("REL_X")) {
            pendingDx += parseLastHexValue(line);
        } else if (line.contains("EV_REL") && line.contains("REL_Y")) {
            pendingDy += parseLastHexValue(line);
        } else if (line.contains("EV_KEY") && line.contains("BTN_LEFT")) {
            String upper = line.toUpperCase(Locale.US);
            if (upper.endsWith("DOWN") || upper.endsWith("00000001")) {
                pendingButton = Boolean.TRUE;
            } else if (upper.endsWith("UP") || upper.endsWith("00000000")) {
                pendingButton = Boolean.FALSE;
            }
        } else if (line.contains("EV_SYN") && line.contains("SYN_REPORT")) {
            Frame frame = null;
            if (pendingDx != 0 || pendingDy != 0 || pendingButton != null) {
                frame = new Frame(pendingDx, pendingDy, pendingButton);
            }
            pendingDx = 0;
            pendingDy = 0;
            pendingButton = null;
            return frame;
        }
        return null;
    }

    static int parseLastHexValue(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0) return 0;
        try {
            long unsigned = Long.parseUnsignedLong(parts[parts.length - 1], 16);
            return (int) unsigned;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
