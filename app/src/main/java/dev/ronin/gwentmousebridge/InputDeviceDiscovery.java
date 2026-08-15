package dev.ronin.gwentmousebridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Selects a relative mouse event node from a fresh /proc/bus/input/devices snapshot. */
final class InputDeviceDiscovery {
    static final class Result {
        final String name;
        final String path;

        Result(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    private InputDeviceDiscovery() {}

    static Result discover(Reader source, String preferredDeviceName) throws IOException {
        String preferred = preferredDeviceName == null ? "" : preferredDeviceName.trim();
        List<Candidate> candidates = new ArrayList<>();

        try (BufferedReader reader = source instanceof BufferedReader
                ? (BufferedReader) source
                : new BufferedReader(source)) {
            String line;
            String currentName = null;
            String currentHandlers = null;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("N: Name=")) {
                    currentName = unquote(trimmed.substring("N: Name=".length()).trim());
                } else if (trimmed.startsWith("H: Handlers=")) {
                    currentHandlers = trimmed.substring("H: Handlers=".length()).trim();
                } else if (trimmed.isEmpty()) {
                    addCandidate(candidates, currentName, currentHandlers, preferred);
                    currentName = null;
                    currentHandlers = null;
                }
            }
            addCandidate(candidates, currentName, currentHandlers, preferred);
        }

        return bestResult(candidates);
    }

    /** Fallback parser for the finite device inventory printed by getevent -pl. */
    static Result discoverGeteventInventory(Reader source, String preferredDeviceName)
            throws IOException {
        String preferred = preferredDeviceName == null ? "" : preferredDeviceName.trim();
        List<Candidate> candidates = new ArrayList<>();

        try (BufferedReader reader = source instanceof BufferedReader
                ? (BufferedReader) source
                : new BufferedReader(source)) {
            String line;
            String currentName = null;
            String currentPath = null;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("add device ")) {
                    addInventoryCandidate(candidates, currentName, currentPath, preferred);
                    currentName = null;
                    currentPath = eventPath(trimmed);
                } else if (trimmed.startsWith("name:")) {
                    currentName = unquote(trimmed.substring("name:".length()).trim());
                }
            }
            addInventoryCandidate(candidates, currentName, currentPath, preferred);
        }

        return bestResult(candidates);
    }

    private static void addCandidate(
            List<Candidate> candidates,
            String name,
            String handlers,
            String preferred) {
        String event = eventHandler(handlers);
        if (event == null || name == null) return;

        String path = "/dev/input/" + event;
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

    private static void addInventoryCandidate(
            List<Candidate> candidates,
            String name,
            String path,
            String preferred) {
        if (name == null || path == null) return;
        boolean exactPreferred = !preferred.isEmpty() && name.equalsIgnoreCase(preferred);
        String lowerName = name.toLowerCase(Locale.US);
        boolean mouseLikeName = lowerName.contains("mouse") || lowerName.contains("pointer");
        int score = exactPreferred ? 4 : mouseLikeName ? 2 : 0;
        if (score > 0) candidates.add(new Candidate(name, path, score));
    }

    private static Result bestResult(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (best == null || candidate.score > best.score) best = candidate;
        }
        return best == null ? null : new Result(best.name, best.path);
    }

    private static String eventPath(String line) {
        String prefix = "/dev/input/event";
        int start = line.indexOf(prefix);
        if (start < 0) return null;
        int end = start + prefix.length();
        while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
        if (end == start + prefix.length()) return null;
        return line.substring(start, end);
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
