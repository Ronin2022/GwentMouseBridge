package dev.ronin.gwentmousebridge;

oneway interface IMouseEventListener {
    // leftButtonState: -1 unchanged, 0 up, 1 down. One callback equals one SYN_REPORT frame.
    void onFrame(int dx, int dy, int leftButtonState);
    void onStatus(String status);
}
