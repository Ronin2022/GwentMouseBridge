package dev.ronin.gwentmousebridge;

/** Minimal JNI wrapper around evdev read/poll and EVIOCGRAB. */
final class NativeInputReader {
    private static final Throwable LOAD_ERROR;

    static {
        Throwable error = null;
        try {
            System.loadLibrary("gmb_input");
        } catch (Throwable t) {
            error = t;
        }
        LOAD_ERROR = error;
    }

    private NativeInputReader() {}

    static void requireAvailable() {
        if (LOAD_ERROR != null) {
            throw new IllegalStateException(
                    "Native input library unavailable: " + safeMessage(LOAD_ERROR),
                    LOAD_ERROR);
        }
    }

    static native int openDevice(String path);
    static native int readEvent(int fd, int[] event, int timeoutMs);
    static native int setExclusive(int fd, boolean enabled);
    static native void closeDevice(int fd);

    static String error(String operation, int result) {
        return operation + " failed (errno " + Math.abs(result) + ')';
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
