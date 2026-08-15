package dev.ronin.gwentmousebridge;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import rikka.shizuku.Shizuku;

public class MouseBridgeAccessibilityService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "GwentMouseBridge";
    private static final int SHELL_UID = 2000;
    private static final long SEGMENT_MS = 18L;
    private static final int CURSOR_SIZE_DP = 28;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager windowManager;
    private CursorOverlayView cursorView;
    private WindowManager.LayoutParams cursorParams;

    private float cursorX;
    private float cursorY;
    private int screenWidth;
    private int screenHeight;
    private boolean gwentForeground;

    private IMouseInputService remoteService;
    private boolean userServiceBinding;

    private boolean leftDown;
    private boolean gestureInFlight;
    private boolean releaseRequested;
    private GestureDescription.StrokeDescription activeStroke;
    private float strokeEndX;
    private float strokeEndY;
    private float pendingX;
    private float pendingY;
    private boolean pendingMove;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> mainHandler.post(this::maybeBindUserService);
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> mainHandler.post(() -> {
        remoteService = null;
        userServiceBinding = false;
        stopInteraction();
        updateCursorVisibility();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        mainHandler.post(() -> {
            if (grantResult == PERMISSION_GRANTED) maybeBindUserService();
            else {
                stopInteraction();
                updateCursorVisibility();
            }
        });
    };

    private final IMouseEventListener eventListener = new IMouseEventListener.Stub() {
        @Override
        public void onMove(int dx, int dy) {
            mainHandler.post(() -> handleMouseMove(dx, dy));
        }

        @Override
        public void onLeftButton(boolean down) {
            mainHandler.post(() -> handleLeftButton(down));
        }

        @Override
        public void onStatus(String status) {
            android.util.Log.i(TAG, status);
        }
    };

    private final ServiceConnection userServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            userServiceBinding = false;
            if (binder == null || !binder.pingBinder() || !isShizukuShellReady()) {
                stopInteraction();
                updateCursorVisibility();
                return;
            }
            remoteService = IMouseInputService.Stub.asInterface(binder);
            try {
                remoteService.startCapture(eventListener, BridgePrefs.PREFERRED_MOUSE);
                updateCursorVisibility();
            } catch (Throwable e) {
                remoteService = null;
                android.util.Log.e(TAG, "Unable to start mouse capture", e);
                updateCursorVisibility();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            userServiceBinding = false;
            remoteService = null;
            stopInteraction();
            updateCursorVisibility();
        }
    };

    private final Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(new ComponentName(BuildConfig.APPLICATION_ID, MouseInputUserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("mouse_reader")
                    .tag("gwent_mouse_reader")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = BridgePrefs.prefs(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        updateScreenBounds(true);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        maybeBindUserService();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;
        String pkg = packageName.toString();
        if (BuildConfig.APPLICATION_ID.equals(pkg)) return;
        setGwentForeground(BridgePrefs.GWENT_PACKAGE.equals(pkg));
    }

    @Override
    public void onInterrupt() {
        stopInteraction();
    }

    @Override
    public void onDestroy() {
        stopInteraction();
        removeCursor();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        unbindUserService();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        stopInteraction();
        updateScreenBounds(false);
        updateCursorPosition();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (BridgePrefs.KEY_ENABLED.equals(key)) {
            if (BridgePrefs.enabled(this)) ensureRemoteCapture();
            else {
                stopInteraction();
                stopRemoteCapture();
            }
            updateCursorVisibility();
        } else if (BridgePrefs.KEY_SHOW_CURSOR.equals(key)) {
            updateCursorVisibility();
        }
    }

    private void ensureRemoteCapture() {
        if (!BridgePrefs.enabled(this)) return;
        IMouseInputService service = remoteService;
        if (service != null) {
            try {
                service.startCapture(eventListener, BridgePrefs.PREFERRED_MOUSE);
                return;
            } catch (Throwable e) {
                remoteService = null;
            }
        }
        maybeBindUserService();
    }

    private void maybeBindUserService() {
        if (!BridgePrefs.enabled(this) || userServiceBinding || remoteService != null) return;
        try {
            if (!isShizukuShellReady()) return;
            userServiceBinding = true;
            Shizuku.bindUserService(userServiceArgs, userServiceConnection);
        } catch (Throwable t) {
            userServiceBinding = false;
            android.util.Log.e(TAG, "Unable to bind Shizuku UserService", t);
        }
    }

    private void unbindUserService() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true);
            }
        } catch (Throwable ignored) {}
        userServiceBinding = false;
        remoteService = null;
    }

    private void stopRemoteCapture() {
        IMouseInputService service = remoteService;
        if (service != null) {
            try {
                service.stopCapture();
            } catch (RemoteException ignored) {}
        }
    }

    private void handleMouseMove(int dx, int dy) {
        if (!BridgePrefs.enabled(this)) return;
        float sensitivity = BridgePrefs.sensitivity(this);
        cursorX = clamp(cursorX + dx * sensitivity, 0f, Math.max(0f, screenWidth - 1f));
        cursorY = clamp(cursorY + dy * sensitivity, 0f, Math.max(0f, screenHeight - 1f));
        updateCursorPosition();

        if (!leftDown) return;
        if (!canInjectIntoGwent()) {
            stopInteraction();
            return;
        }
        pendingX = cursorX;
        pendingY = cursorY;
        pendingMove = true;
        dispatchNextDragSegmentIfPossible();
    }

    private void handleLeftButton(boolean down) {
        if (!BridgePrefs.enabled(this)) return;
        if (down) {
            if (leftDown || !canInjectIntoGwent()) return;
            leftDown = true;
            releaseRequested = false;
            pendingMove = false;
            beginPress(cursorX, cursorY);
        } else {
            if (!leftDown) return;
            leftDown = false;
            releaseRequested = true;
            pendingX = cursorX;
            pendingY = cursorY;
            dispatchNextDragSegmentIfPossible();
        }
    }

    private boolean canInjectIntoGwent() {
        if (!gwentForeground
                || !BridgePrefs.enabled(this)
                || remoteService == null
                || !isShizukuShellReady()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            CharSequence pkg = root.getPackageName();
            return pkg != null && BridgePrefs.GWENT_PACKAGE.contentEquals(pkg);
        } finally {
            root.recycle();
        }
    }

    private void beginPress(float x, float y) {
        if (gestureInFlight || activeStroke != null) {
            stopInteraction();
            return;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true);
        activeStroke = stroke;
        strokeEndX = x;
        strokeEndY = y;
        gestureInFlight = true;
        boolean accepted = dispatchStroke(stroke, () -> {
            gestureInFlight = false;
            dispatchNextDragSegmentIfPossible();
        });
        if (!accepted) resetGestureState();
    }

    private void dispatchNextDragSegmentIfPossible() {
        if (gestureInFlight || activeStroke == null) return;
        if (!canInjectIntoGwent()) {
            finishStrokeImmediately();
            return;
        }

        if (releaseRequested) {
            float endX = cursorX;
            float endY = cursorY;
            dispatchContinuation(endX, endY, false);
            return;
        }

        if (leftDown && pendingMove) {
            pendingMove = false;
            dispatchContinuation(pendingX, pendingY, true);
        }
    }

    private void dispatchContinuation(float x, float y, boolean willContinue) {
        Path path = new Path();
        path.moveTo(strokeEndX, strokeEndY);
        path.lineTo(x, y);

        GestureDescription.StrokeDescription next;
        try {
            next = activeStroke.continueStroke(path, 0, SEGMENT_MS, willContinue);
        } catch (Throwable t) {
            resetGestureState();
            return;
        }
        activeStroke = next;
        strokeEndX = x;
        strokeEndY = y;
        gestureInFlight = true;

        boolean accepted = dispatchStroke(next, () -> {
            gestureInFlight = false;
            if (!willContinue) {
                resetGestureState();
            } else {
                dispatchNextDragSegmentIfPossible();
            }
        });
        if (!accepted) resetGestureState();
    }

    private boolean dispatchStroke(GestureDescription.StrokeDescription stroke, Runnable completed) {
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                mainHandler.post(completed);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                mainHandler.post(MouseBridgeAccessibilityService.this::resetGestureState);
            }
        }, mainHandler);
    }

    private void finishStrokeImmediately() {
        if (activeStroke == null || gestureInFlight) {
            if (gestureInFlight) releaseRequested = true;
            else resetGestureState();
            return;
        }
        releaseRequested = true;
        // A zero-distance final continuation only lifts the pointer that this service already
        // pressed. It is required to avoid leaving a continued stroke held after foreground loss.
        dispatchContinuation(strokeEndX, strokeEndY, false);
    }

    private void stopInteraction() {
        leftDown = false;
        releaseRequested = true;
        pendingMove = false;
        if (activeStroke != null && !gestureInFlight) finishStrokeImmediately();
    }

    private void resetGestureState() {
        leftDown = false;
        gestureInFlight = false;
        releaseRequested = false;
        pendingMove = false;
        activeStroke = null;
    }

    private void updateScreenBounds(boolean recenter) {
        Point size = new Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        screenWidth = Math.max(1, size.x);
        screenHeight = Math.max(1, size.y);
        if (recenter || cursorX <= 0f || cursorY <= 0f) {
            cursorX = screenWidth / 2f;
            cursorY = screenHeight / 2f;
        } else {
            cursorX = clamp(cursorX, 0f, screenWidth - 1f);
            cursorY = clamp(cursorY, 0f, screenHeight - 1f);
        }
    }

    private void updateCursorVisibility() {
        boolean shouldShow = gwentForeground
                && BridgePrefs.enabled(this)
                && BridgePrefs.showCursor(this)
                && remoteService != null
                && isShizukuShellReady();
        if (shouldShow) ensureCursor();
        else removeCursor();
    }

    private void ensureCursor() {
        if (cursorView != null) {
            updateCursorPosition();
            return;
        }
        int sizePx = Math.round(CURSOR_SIZE_DP * getResources().getDisplayMetrics().density);
        cursorView = new CursorOverlayView(this);
        cursorParams = new WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        cursorParams.gravity = Gravity.TOP | Gravity.START;
        updateCursorLayoutCoordinates();
        try {
            windowManager.addView(cursorView, cursorParams);
        } catch (Throwable t) {
            cursorView = null;
            cursorParams = null;
        }
    }

    private void updateCursorPosition() {
        if (cursorView == null || cursorParams == null) return;
        updateCursorLayoutCoordinates();
        try {
            windowManager.updateViewLayout(cursorView, cursorParams);
        } catch (Throwable ignored) {}
    }

    private void updateCursorLayoutCoordinates() {
        if (cursorParams == null) return;
        cursorParams.x = Math.round(cursorX - cursorParams.width / 2f);
        cursorParams.y = Math.round(cursorY - cursorParams.height / 2f);
    }

    private void removeCursor() {
        if (cursorView == null) return;
        try {
            windowManager.removeView(cursorView);
        } catch (Throwable ignored) {}
        cursorView = null;
        cursorParams = null;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void setGwentForeground(boolean foreground) {
        if (foreground == gwentForeground) return;
        gwentForeground = foreground;
        if (foreground) {
            updateScreenBounds(true);
        } else {
            stopInteraction();
        }
        updateCursorVisibility();
    }

    private static boolean isShizukuShellReady() {
        try {
            return Shizuku.pingBinder()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PERMISSION_GRANTED
                    && Shizuku.getUid() == SHELL_UID;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
