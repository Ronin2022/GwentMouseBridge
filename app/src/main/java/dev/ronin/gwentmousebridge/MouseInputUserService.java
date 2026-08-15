package dev.ronin.gwentmousebridge;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
                    DeviceCandidate device = discoverDevice(preferredDeviceName, session.listener);
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
        java.lang.Process process = new ProcessBuilder("/system/bin/getevent", "-l", path)
                .redirectErrorStream(true)
                .start();
        session.process = process;
        GetEventParser parser = new GetEventParser();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while (isActive(session) && (line = reader.readLine()) != null) {
                GetEventParser.Frame frame = parser.accept(line);
                if (frame == null) continue;
                try {
                    if (frame.dx != 0 || frame.dy != 0) {
                        session.listener.onMove(frame.dx, frame.dy);
                    }
                    if (frame.leftButtonDown != null) {
                        session.listener.onLeftButton(frame.leftButtonDown);
                    }
                } catch (RemoteException e) {
                    session.running.set(false);
                }
            }
        } finally {
            if (session.process == process) session.process = null;
            process.destroy();
        }
    }

    private DeviceCandidate discoverDevice(String preferredDeviceName, IMouseEventListener statusListener) {
        String preferred = preferredDeviceName == null ? "" : preferredDeviceName.trim();
        List<DeviceCandidate> candidates = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            String line;
            String currentName = null;
            String currentHandlers = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("N: Name=")) {
                    currentName = unquote(line.substring("N: Name=".length()).trim());
                } else if (line.startsWith("H: Handlers=")) {
                    currentHandlers = line.substring("H: Handlers=".length()).trim();
                } else if (line.trim().isEmpty()) {
                    addCandidate(candidates, currentName, currentHandlers, preferred);
                    currentName = null;
                    currentHandlers = null;
                }
            }
            addCandidate(candidates, currentName, currentHandlers, preferred);
        } catch (Throwable t) {
            sendStatus(statusListener, "Device discovery error: " + t.getMessage());
            return null;
        }

        DeviceCandidate best = null;
        for (DeviceCandidate candidate : candidates) {
            if (best == null || candidate.score > best.score) best = candidate;
        }
        return best;
    }

    private static void addCandidate(
            List<DeviceCandidate> candidates,
            String name,
            String handlers,
            String preferred) {
        String event = eventHandler(handlers);
        if (event == null || name == null) return;

        String path = "/dev/input/" + event;
        File input = new File(path);
        if (!input.exists() || !input.canRead()) return;

        boolean mouseHandler = hasMouseHandler(handlers);
        boolean exactPreferred = !preferred.isEmpty() && name.equalsIgnoreCase(preferred);
        String lowerName = name.toLowerCase(Locale.US);
        boolean mouseLikeName = lowerName.contains("mouse") || lowerName.contains("pointer");

        int score = 0;
        if (exactPreferred && mouseHandler) score = 4;
        else if (exactPreferred) score = 3;
        else if (mouseHandler && mouseLikeName) score = 2;
        else if (mouseHandler) score = 1;

        if (score > 0) candidates.add(new DeviceCandidate(name, path, score));
    }

    private static String eventHandler(String handlers) {
        if (handlers == null) return null;
        for (String token : handlers.split("\\s+")) {
            if (token.startsWith("event")) return token;
        }
        return null;
    }

    private static boolean hasMouseHandler(String handlers) {
        if (handlers == null) return false;
        for (String token : handlers.split("\\s+")) {
            if (token.startsWith("mouse")) return true;
        }
        return false;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
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

    private static final class DeviceCandidate {
        final String name;
        final String path;
        final int score;

        DeviceCandidate(String name, String path, int score) {
            this.name = name;
            this.path = path;
            this.score = score;
        }
    }
}
