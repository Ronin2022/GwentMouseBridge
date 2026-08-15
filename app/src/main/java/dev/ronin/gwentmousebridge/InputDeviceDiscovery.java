package dev.ronin.gwentmousebridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Selects a readable relative mouse event node from a fresh /proc/bus/input/devices snapshot. */
final class InputDeviceDiscovery {
    interface PathAccess {
        boolean isReadable(String path);
    }

    static final class Result {
        final String name;
        final String path;

        Result(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    private InputDeviceDiscovery() {}

    static Result discover(Reader source, String preferredDeviceName, PathAccess pathAccess)
            throws IOException {
        String preferred = preferredDeviceName == null ? "" : preferredDeviceName.trim();
        List<Candidate> candidates = new ArrayList<>();

        try (BufferedReader reader = source instanceof BufferedReader
                ? (BufferedReader) source
                : new BufferedReader(source)) {
            String line;
            String currentName = null;
            String currentHandlers = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("N: Name=")) {
                    currentName = unquote(line.substring("N: Name=".length()).trim());
                } else if (line.startsWith("H: Handlers=")) {
                    currentHandlers = line.substring("H: Handlers=".length()).trim();
                } else if (line.trim().isEmpty()) {
                    addCandidate(candidates, currentName, currentHandlers, preferred, pathAccess);
                    currentName = null;
                    currentHandlers = null;
                }
            }
            addCandidate(candidates, currentName, currentHandlers, preferred, pathAccess);
        }

        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (best == null || candidate.score > best.score) best = candidate;
        }
        return best == null ? null : new Result(best.name, best.path);
    }

    private static void addCandidate(
            List<Candidate> candidates,
            String name,
            String handlers,
            String preferred,
            PathAccess pathAccess) {
        String event = eventHandler(handlers);
        if (event == null || name == null) return;

        String path = "/dev/input/" + event;
        if (!pathAccess.isReadable(path)) return;

        boolean mouseHandler = hasMouseHandler(handlers);
        boolean exactPreferred = !preferred.isEmpty() && name.equalsIgnoreCase(preferred);
        String lowerName = name.toLowerCase(Locale.US);
        boolean mouseLikeName = lowerName.contains("mouse") || lowerName.contains("pointer");

        int score = 0;
        if (exactPreferred && mouseHandler) score = 4;
        else if (exactPreferred) score = 3;
        else if (mouseHandler && mouseLikeName) score = 2;
        else if (mouseHandler) score = 1;

        if (score > 0) candidates.add(new Candidate(name, path, score));
    }

    private static String eventHandler(String handlers) {
        if (handlers == null) return null;
        for (String token : handlers.split("\\s+")) {
            if (token.matches("event\\d+")) return token;
        }
        return null;
    }

    private static boolean hasMouseHandler(String handlers) {
        if (handlers == null) return false;
        for (String token : handlers.split("\\s+")) {
            if (token.matches("mouse\\d+")) return true;
        }
        return false;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static final class Candidate {
        final String name;
        final String path;
        final int score;

        Candidate(String name, String path, int score) {
            this.name = name;
            this.path = path;
            this.score = score;
        }
    }
}
