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

import java.util.List;

import rikka.shizuku.Shizuku;

public class MouseBridgeAccessibilityService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "GwentMouseBridge";
    private static final int SHELL_UID = 2000;
    private static final long SEGMENT_MS = 18L;
    private static final long DIAGNOSTIC_WRITE_INTERVAL_MS = 750L;
    private static final int CURSOR_SIZE_DP = 28;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager windowManager;
    private CursorOverlayView cursorView;
    private WindowManager.LayoutParams cursorParams;

    private final VirtualCursorState cursor = new VirtualCursorState();
    private final MouseGestureStateMachine mouseState = new MouseGestureStateMachine();
    private boolean gwentForeground;

    private IMouseInputService remoteService;
    private boolean userServiceBinding;
    private long readerFrameCount;
    private long readerMotionFrameCount;
    private long lastDiagnosticWriteTime;

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
        recordReaderStatus("Shizuku binder disconnected");
        stopInteraction();
        updateCursorVisibility();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        mainHandler.post(() -> {
            if (grantResult == PERMISSION_GRANTED) maybeBindUserService();
            else {
                recordReaderStatus("Shizuku permission unavailable");
                stopInteraction();
                updateCursorVisibility();
            }
        });
    };

    private final IMouseEventListener eventListener = new IMouseEventListener.Stub() {
        @Override
        public void onFrame(int dx, int dy, int leftButtonState) {
            mainHandler.post(() -> handleMouseFrame(dx, dy, leftButtonState));
        }

        @Override
        public void onStatus(String status) {
            android.util.Log.i(TAG, status);
            mainHandler.post(() -> recordReaderStatus(status));
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
                resetReaderDiagnostics("Starting mouse capture");
                remoteService.startCapture(eventListener, BridgePrefs.PREFERRED_MOUSE);
                updateCursorVisibility();
            } catch (Throwable e) {
                remoteService = null;
                stopInteraction();
                android.util.Log.e(TAG, "Unable to start mouse capture", e);
                updateCursorVisibility();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            userServiceBinding = false;
            remoteService = null;
            recordReaderStatus("Mouse UserService disconnected");
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
                stopInteraction();
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

    private void handleMouseFrame(int dx, int dy, int buttonState) {
        recordReaderFrame(dx, dy);
        if (!BridgePrefs.enabled(this)) {
            stopInteraction();
            return;
        }

        float frameStartX = cursor.x();
        float frameStartY = cursor.y();
        boolean moved = dx != 0 || dy != 0;
        if (moved) {
            cursor.move(dx, dy, BridgePrefs.sensitivity(this));
            updateCursorPosition();
        }

        List<MouseGestureStateMachine.Action> actions = mouseState.onFrame(
                moved,
                buttonState,
                canInjectIntoGwent());
        for (MouseGestureStateMachine.Action action : actions) {
            switch (action) {
                case PRESS:
                    leftDown = true;
                    releaseRequested = false;
                    pendingMove = false;
                    // If button-down and motion share a SYN frame, touch starts at the
                    // previous pointer position and the frame becomes one drag segment.
                    beginPress(moved ? frameStartX : cursor.x(), moved ? frameStartY : cursor.y());
                    break;
                case DRAG_START:
                case DRAG_UPDATE:
                    if (!leftDown || activeStroke == null) {
                        resetGestureState();
                        break;
                    }
                    pendingX = cursor.x();
                    pendingY = cursor.y();
                    pendingMove = true;
                    dispatchNextDragSegmentIfPossible();
                    break;
                case TAP:
                case DRAG_END:
                    if (!leftDown || activeStroke == null) {
                        resetGestureState();
                        break;
                    }
                    leftDown = false;
                    releaseRequested = true;
                    pendingMove = false;
                    pendingX = cursor.x();
                    pendingY = cursor.y();
                    dispatchNextDragSegmentIfPossible();
                    break;
                case ABORT:
                    stopGestureInteraction();
                    break;
            }
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
            float endX = cursor.x();
            float endY = cursor.y();
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
        try {
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
        } catch (Throwable t) {
            android.util.Log.e(TAG, "Gesture dispatch failed", t);
            return false;
        }
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
        mouseState.abort();
        stopGestureInteraction();
    }

    private void stopGestureInteraction() {
        leftDown = false;
        releaseRequested = true;
        pendingMove = false;
        if (activeStroke != null && !gestureInFlight) finishStrokeImmediately();
    }

    private void resetGestureState() {
        mouseState.reset();
        leftDown = false;
        gestureInFlight = false;
        releaseRequested = false;
        pendingMove = false;
        activeStroke = null;
    }

    private void updateScreenBounds(boolean recenter) {
        Point size = new Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        cursor.updateBounds(size.x, size.y, recenter);
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
        cursorParams.x = Math.round(cursor.x() - cursorParams.width / 2f);
        cursorParams.y = Math.round(cursor.y() - cursorParams.height / 2f);
    }

    private void removeCursor() {
        if (cursorView == null) return;
        try {
            windowManager.removeView(cursorView);
        } catch (Throwable ignored) {}
        cursorView = null;
        cursorParams = null;
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

    private void resetReaderDiagnostics(String status) {
        readerFrameCount = 0L;
        readerMotionFrameCount = 0L;
        lastDiagnosticWriteTime = 0L;
        if (prefs == null) return;
        prefs.edit()
                .putString(BridgePrefs.KEY_READER_STATUS, status)
                .putLong(BridgePrefs.KEY_READER_FRAME_COUNT, 0L)
                .putLong(BridgePrefs.KEY_READER_MOTION_FRAME_COUNT, 0L)
                .putLong(BridgePrefs.KEY_READER_LAST_FRAME_TIME, 0L)
                .apply();
    }

    private void recordReaderStatus(String status) {
        if (prefs == null) return;
        prefs.edit()
                .putString(
                        BridgePrefs.KEY_READER_STATUS,
                        status == null ? "Unknown reader status" : status)
                .apply();
    }

    private void recordReaderFrame(int dx, int dy) {
        readerFrameCount++;
        if (dx != 0 || dy != 0) readerMotionFrameCount++;
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastDiagnosticWriteTime != 0L
                && now - lastDiagnosticWriteTime < DIAGNOSTIC_WRITE_INTERVAL_MS) return;
        lastDiagnosticWriteTime = now;
        if (prefs == null) return;
        prefs.edit()
                .putLong(BridgePrefs.KEY_READER_FRAME_COUNT, readerFrameCount)
                .putLong(BridgePrefs.KEY_READER_MOTION_FRAME_COUNT, readerMotionFrameCount)
                .putLong(BridgePrefs.KEY_READER_LAST_FRAME_TIME, System.currentTimeMillis())
                .apply();
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
