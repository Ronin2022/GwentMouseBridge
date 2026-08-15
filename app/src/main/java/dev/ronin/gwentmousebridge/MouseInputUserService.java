package dev.ronin.gwentmousebridge;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs inside a Shizuku UserService process with shell UID. */
public class MouseInputUserService extends IMouseInputService.Stub {
    private static final int SHELL_UID = 2000;
    private static final int READ_POLL_MS = 250;

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

        try {
            NativeInputReader.requireAvailable();
        } catch (Throwable t) {
            sendStatus(newListener, safeMessage(t));
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
    public boolean setExclusiveCapture(boolean enabled) {
        CaptureSession session = activeSession;
        if (session == null || !session.running.get()) return false;
        session.exclusiveRequested = enabled;
        return applyExclusiveMode(session, enabled, true);
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

    private void terminateSession(CaptureSession session) {
        session.running.set(false);
        session.exclusiveRequested = false;
        // Release synchronously so the system mouse is restored even while the read thread is
        // still leaving its bounded native poll.
        applyExclusiveMode(session, false, false);
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
                    readNativeEvents(session, device);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    sendStatus(session.listener, "Mouse reader error: " + safeMessage(t));
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

    private void readNativeEvents(CaptureSession session, InputDeviceDiscovery.Result device)
            throws InterruptedException {
        int openedFd = NativeInputReader.openDevice(device.path);
        if (openedFd < 0) {
            sendStatus(session.listener, NativeInputReader.error("Opening " + device.path, openedFd));
            return;
        }

        synchronized (session.deviceLock) {
            if (!isActive(session)) {
                NativeInputReader.closeDevice(openedFd);
                return;
            }
            session.fd = openedFd;
        }

        sendStatus(session.listener, "Reading " + device.name + " at " + device.path);
        applyExclusiveMode(session, session.exclusiveRequested, true);
        LinuxInputFrameAccumulator accumulator = new LinuxInputFrameAccumulator();
        int[] event = new int[3];
        boolean inputReported = false;

        try {
            while (isActive(session)) {
                int result = NativeInputReader.readEvent(openedFd, event, READ_POLL_MS);
                if (result == 0) continue;
                if (result < 0) {
                    sendStatus(session.listener, NativeInputReader.error("Reading " + device.path, result));
                    return;
                }
                if (!inputReported) {
                    inputReported = true;
                    sendStatus(session.listener, "Parsed native input frames from " + device.path);
                }
                GetEventParser.Frame frame = accumulator.accept(event[0], event[1], event[2]);
                if (frame != null) sendFrame(session, frame);
            }
        } finally {
            synchronized (session.deviceLock) {
                if (session.fd == openedFd) {
                    if (session.exclusiveActive) NativeInputReader.setExclusive(openedFd, false);
                    session.exclusiveActive = false;
                    session.fd = -1;
                }
            }
            NativeInputReader.closeDevice(openedFd);
            if (isActive(session)) {
                sendExclusiveStatus(session, false, "Mouse disconnected; exclusive capture released");
            }
        }
    }

    private void sendFrame(CaptureSession session, GetEventParser.Frame frame) {
        if (!isActive(session)) return;
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

    private boolean applyExclusiveMode(CaptureSession session, boolean enabled, boolean report) {
        String message;
        boolean active;
        synchronized (session.deviceLock) {
            int fd = session.fd;
            if (fd < 0) {
                session.exclusiveActive = false;
                active = false;
                message = enabled
                        ? "Exclusive capture requested; waiting for mouse device"
                        : "Exclusive capture inactive";
            } else if (session.exclusiveActive == enabled) {
                active = session.exclusiveActive;
                message = active ? "Exclusive capture active" : "Exclusive capture inactive";
            } else {
                int result = NativeInputReader.setExclusive(fd, enabled);
                if (result == 0) {
                    session.exclusiveActive = enabled;
                    active = enabled;
                    message = enabled ? "Exclusive capture active" : "Exclusive capture released";
                } else {
                    if (!enabled) session.exclusiveActive = false;
                    active = session.exclusiveActive;
                    message = NativeInputReader.error(
                            enabled ? "EVIOCGRAB enable" : "EVIOCGRAB release",
                            result);
                }
            }
        }
        if (report) sendExclusiveStatus(session, active, message);
        return active;
    }

    private InputDeviceDiscovery.Result discoverDevice(
            String preferredDeviceName,
            IMouseEventListener statusListener) {
        try {
            InputDeviceDiscovery.Result result = InputDeviceDiscovery.discover(
                    new FileReader("/proc/bus/input/devices"),
                    preferredDeviceName);
            if (result != null) return result;
            sendStatus(statusListener, "/proc inventory did not identify the mouse; trying getevent inventory.");
        } catch (Throwable t) {
            sendStatus(statusListener, "/proc discovery unavailable; trying getevent inventory: " + safeMessage(t));
        }

        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getevent", "-pl")
                    .redirectErrorStream(true)
                    .start();
            InputDeviceDiscovery.Result result;
            try (InputStreamReader output = new InputStreamReader(process.getInputStream())) {
                result = InputDeviceDiscovery.discoverGeteventInventory(output, preferredDeviceName);
            }
            int exitCode = process.waitFor();
            if (result == null) {
                sendStatus(statusListener, "getevent inventory found no matching mouse (exit " + exitCode + ").");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            sendStatus(statusListener, "getevent inventory error: " + safeMessage(t));
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static void sendStatus(IMouseEventListener listener, String message) {
        if (listener == null) return;
        try {
            listener.onStatus(message == null ? "Unknown input reader error." : message);
        } catch (RemoteException ignored) {}
    }

    private static void sendExclusiveStatus(
            CaptureSession session,
            boolean active,
            String message) {
        try {
            session.listener.onExclusiveCaptureChanged(active, message);
        } catch (RemoteException ignored) {}
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.isEmpty()
                ? (throwable == null ? "unknown" : throwable.getClass().getSimpleName())
                : message;
    }

    private final class CaptureSession {
        final IMouseEventListener listener;
        final AtomicBoolean running = new AtomicBoolean(false);
        final IBinder.DeathRecipient deathRecipient;
        final Object deviceLock = new Object();
        volatile Thread thread;
        volatile String devicePath;
        volatile boolean exclusiveRequested;
        boolean exclusiveActive;
        int fd = -1;

        CaptureSession(IMouseEventListener listener) {
            this.listener = listener;
            this.deathRecipient = () -> onListenerDied(this);
        }
    }
}
