package dev.ronin.gwentmousebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class ForegroundWindowTrackerTest {
    private static final String GWENT = "com.cdprojektred.gwent";

    @Test
    public void nonFocusedNotificationOverlayDoesNotDisplaceFocusedGwent() {
        ForegroundWindowTracker tracker = new ForegroundWindowTracker(GWENT);
        tracker.rememberTargetWindow(41);

        assertEquals(
                ForegroundWindowTracker.Decision.GWENT,
                tracker.evaluate(List.of(
                        window(90, false, "com.android.systemui"),
                        window(41, true, null))));
    }

    @Test
    public void focusedNotificationShadeFailsClosed() {
        ForegroundWindowTracker tracker = new ForegroundWindowTracker(GWENT);
        tracker.rememberTargetWindow(41);

        assertEquals(
                ForegroundWindowTracker.Decision.OTHER,
                tracker.evaluate(List.of(
                        window(90, true, "com.android.systemui"),
                        window(41, false, GWENT))));
    }

    @Test
    public void focusedOtherApplicationFailsClosed() {
        ForegroundWindowTracker tracker = new ForegroundWindowTracker(GWENT);
        tracker.rememberTargetWindow(41);

        assertEquals(
                ForegroundWindowTracker.Decision.OTHER,
                tracker.evaluate(List.of(window(55, true, "com.example.other"))));
    }

    @Test
    public void focusedTargetPackageRecoversAfterWindowIdChanges() {
        ForegroundWindowTracker tracker = new ForegroundWindowTracker(GWENT);
        tracker.rememberTargetWindow(41);

        assertEquals(
                ForegroundWindowTracker.Decision.GWENT,
                tracker.evaluate(List.of(window(42, true, GWENT))));
        assertEquals(
                ForegroundWindowTracker.Decision.GWENT,
                tracker.evaluate(List.of(window(42, true, null))));
    }

    @Test
    public void missingFocusIsUnknownInsteadOfAssumedSafe() {
        ForegroundWindowTracker tracker = new ForegroundWindowTracker(GWENT);
        assertEquals(
                ForegroundWindowTracker.Decision.UNKNOWN,
                tracker.evaluate(Collections.singletonList(window(90, false, "com.android.systemui"))));
        assertEquals(
                ForegroundWindowTracker.Decision.UNKNOWN,
                tracker.evaluate(Collections.emptyList()));
    }

    private static ForegroundWindowTracker.WindowSnapshot window(
            int id,
            boolean focused,
            String packageName) {
        return new ForegroundWindowTracker.WindowSnapshot(id, focused, packageName);
    }
}
