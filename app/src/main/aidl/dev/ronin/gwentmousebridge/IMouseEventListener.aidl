package dev.ronin.gwentmousebridge;

oneway interface IMouseEventListener {
    void onMove(int dx, int dy);
    void onLeftButton(boolean down);
    void onStatus(String status);
}
