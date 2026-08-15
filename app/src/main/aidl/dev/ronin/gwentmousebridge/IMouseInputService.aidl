package dev.ronin.gwentmousebridge;

import dev.ronin.gwentmousebridge.IMouseEventListener;

interface IMouseInputService {
    void destroy() = 16777114;
    void startCapture(IMouseEventListener listener, String preferredDeviceName) = 1;
    void stopCapture() = 2;
    String getDevicePath() = 3;
    boolean isRunning() = 4;
}
