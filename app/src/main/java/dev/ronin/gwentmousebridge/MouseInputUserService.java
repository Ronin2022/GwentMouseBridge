package dev.ronin.gwentmousebridge;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs inside a Shizuku UserService process with shell UID. */
public class MouseInputUserService extends IMouseInputService.Stub {
    private static final int SHELL_UID = 2000;

    private volatile CaptureSession activeSession;
    private volatile String lastDevicePath;

    public MouseInputUserService() {}

    @Override
    public synchronized void startCapture(IMouseEventListener newListener, String preferredDeviceName) {
        stopCaptureInternal();
        if (newListener == null) return;

        int uid = android.os.Process.myUid();
        if (uid != SHELL_UID) {
            sendStatus(newListener, "Input capture refused: expected shell UID 2000, got " + uid + '.');
            return;
        }

        CaptureSession session = new CaptureSession(newListener);
        try {
            newListener.asBinder().linkToDeath(session.deathRecipient, 0);
        } catch (RemoteException e) {
            sendStatus(newListener, "Input capture refused: listener is already disconnected.");
            return;
        }

        activeSession = session;
        session.running.set(true);
        session.thread = new Thread(
                () -> captureLoop(session, preferredDeviceName),
                "gwent-mouse-reader");
        session.thread.setDaemon(true);
        session.thread.start();
    }

    @Override
    public synchronized void stopCapture() {
        stopCaptureInternal();
    }

    @Override
    public String getDevicePath() {
        CaptureSession session = activeSession;
        return session != null && session.devicePath != null ? session.devicePath : lastDevicePath;
    }

    @Override
    public boolean isRunning() {
        CaptureSession session = activeSession;
        return session != null && session.running.get();
    }

    @Override
    public void destroy() {
        synchronized (this) {
            stopCaptureInternal();
        }
        System.exit(0);
    }

    private void onListenerDied(CaptureSession session) {
        synchronized (this) {
            if (activeSession == session) {
                activeSession = null;
                terminateSession(session);
            }
        }
    }

    private void stopCaptureInternal() {
        CaptureSession session = activeSession;
        activeSession = null;
        if (session != null) terminateSession(session);
    }

    private static void terminateSession(CaptureSession session) {
        session.running.set(false);
        java.lang.Process process = session.process;
        session.process = null;
        if (process != null) process.destroy();
        Thread thread = session.thread;
        session.thread = null;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        try {
            session.listener.asBinder().unlinkToDeath(session.deathRecipient, 0);
        } catch (Throwable ignored) {}
    }

    private void captureLoop(CaptureSession session, String preferredDeviceName) {
        try {
            while (isActive(session)) {
                try {
                    InputDeviceDiscovery.Result device = discoverDevice(preferredDeviceName, session.listener);
                    if (device == null) {
                        sendStatus(session.listener, "Mouse input device not found; retrying.");
                        Thread.sleep(1000);
                        continue;
                    }

                    session.devicePath = device.path;
                    lastDevicePath = device.path;
                    sendStatus(session.listener, "Reading " + device.name + " at " + device.path);
                    readGetevent(session, device.path);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    sendStatus(session.listener, "Mouse reader error: " + t.getMessage());
                }

                if (isActive(session)) Thread.sleep(500);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                if (activeSession == session) activeSession = null;
            }
            terminateSession(session);
        }
    }

    private boolean isActive(CaptureSession session) {
        return activeSession == session && session.running.get();
    }

    private void readGetevent(CaptureSession session, String path) throws Exception {
        // Match the exact labelled + timestamped command proven on the Huawei tablet.
        java.lang.Process process = new ProcessBuilder("/system/bin/getevent", "-lt", path)
                .redirectErrorStream(true)
                .start();
        session.process = process;
        GetEventParser parser = new GetEventParser();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            boolean rawInputReported = false;
            boolean parsedInputReported = false;
            while (isActive(session) && (line = reader.readLine()) != null) {
                if (!rawInputReported && !line.trim().isEmpty()) {
                    rawInputReported = true;
                    sendStatus(session.listener, "Raw input detected at " + path);
                }
                GetEventParser.Frame frame = parser.accept(line);
                if (frame == null) continue;
                if (!parsedInputReported) {
                    parsedInputReported = true;
                    sendStatus(session.listener, "Parsed input frames from " + path);
                }
                try {
                    int buttonState = frame.leftButtonDown == null
                            ? MouseGestureStateMachine.BUTTON_UNCHANGED
                            : frame.leftButtonDown
                                    ? MouseGestureStateMachine.BUTTON_DOWN
                                    : MouseGestureStateMachine.BUTTON_UP;
                    session.listener.onFrame(frame.dx, frame.dy, buttonState);
                } catch (RemoteException e) {
                    session.running.set(false);
                }
            }
        } finally {
            if (session.process == process) session.process = null;
            process.destroy();
        }
    }

    private InputDeviceDiscovery.Result discoverDevice(
            String preferredDeviceName,
            IMouseEventListener statusListener) {
        try {
            return InputDeviceDiscovery.discover(
                    new FileReader("/proc/bus/input/devices"),
                    preferredDeviceName,
                    path -> {
                        File input = new File(path);
                        return input.exists() && input.canRead();
                    });
        } catch (Throwable t) {
            sendStatus(statusListener, "Device discovery error: " + t.getMessage());
            return null;
        }
    }

    private static void sendStatus(IMouseEventListener listener, String message) {
        if (listener == null) return;
        try {
            listener.onStatus(message == null ? "Unknown input reader error." : message);
        } catch (RemoteException ignored) {}
    }

    private final class CaptureSession {
        final IMouseEventListener listener;
        final AtomicBoolean running = new AtomicBoolean(false);
        final IBinder.DeathRecipient deathRecipient;
        volatile Thread thread;
        volatile java.lang.Process process;
        volatile String devicePath;

        CaptureSession(IMouseEventListener listener) {
            this.listener = listener;
            this.deathRecipient = () -> onListenerDied(this);
        }
    }
}
