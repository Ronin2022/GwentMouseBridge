package dev.ronin.gwentmousebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VirtualCursorStateTest {
    @Test
    public void tabletBoundsAreRuntimeSizedCenteredAndClamped() {
        VirtualCursorState cursor = new VirtualCursorState();
        cursor.updateBounds(2800, 1840, true);
        assertEquals(1400f, cursor.x(), 0f);
        assertEquals(920f, cursor.y(), 0f);

        cursor.move(10000, -10000, 1f);
        assertEquals(2799f, cursor.x(), 0f);
        assertEquals(0f, cursor.y(), 0f);
    }

    @Test
    public void orientationChangeClampsWithoutHardCodedResolution() {
        VirtualCursorState cursor = new VirtualCursorState();
        cursor.updateBounds(2800, 1840, true);
        cursor.move(1000, 500, 1f);
        cursor.updateBounds(1840, 2800, false);
        assertEquals(1839f, cursor.x(), 0f);
        assertEquals(1420f, cursor.y(), 0f);
    }
}
