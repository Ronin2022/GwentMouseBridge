package dev.ronin.gwentmousebridge;

/** Coalesces numeric Linux input_event records at EV_SYN/SYN_REPORT boundaries. */
final class LinuxInputFrameAccumulator {
    static final int EV_SYN = 0x00;
    static final int EV_KEY = 0x01;
    static final int EV_REL = 0x02;
    static final int SYN_REPORT = 0x00;
    static final int SYN_DROPPED = 0x03;
    static final int REL_X = 0x00;
    static final int REL_Y = 0x01;
    static final int BTN_LEFT = 0x110; // BTN_MOUSE is the same canonical numeric code.

    private int pendingDx;
    private int pendingDy;
    private Boolean pendingLeftButtonDown;

    GetEventParser.Frame accept(int type, int code, int value) {
        if (type == EV_REL) {
            if (code == REL_X) pendingDx += value;
            else if (code == REL_Y) pendingDy += value;
            return null;
        }
        if (type == EV_KEY && code == BTN_LEFT) {
            pendingLeftButtonDown = value != 0;
            return null;
        }
        if (type == EV_SYN) {
            if (code == SYN_REPORT) return flush();
            if (code == SYN_DROPPED) clear();
        }
        return null;
    }

    private GetEventParser.Frame flush() {
        if (pendingDx == 0 && pendingDy == 0 && pendingLeftButtonDown == null) return null;
        GetEventParser.Frame frame =
                new GetEventParser.Frame(pendingDx, pendingDy, pendingLeftButtonDown);
        clear();
        return frame;
    }

    private void clear() {
        pendingDx = 0;
        pendingDy = 0;
        pendingLeftButtonDown = null;
    }
}
