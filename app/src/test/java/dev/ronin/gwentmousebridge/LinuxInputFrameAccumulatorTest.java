package dev.ronin.gwentmousebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinuxInputFrameAccumulatorTest {
    @Test
    public void numericEventsCoalesceXyAndButtonAtSynReport() {
        LinuxInputFrameAccumulator accumulator = new LinuxInputFrameAccumulator();
        assertNull(accumulator.accept(1, 0x110, 1));
        assertNull(accumulator.accept(2, 0, 8));
        assertNull(accumulator.accept(2, 1, -4));

        GetEventParser.Frame frame = accumulator.accept(0, 0, 0);
        assertEquals(8, frame.dx);
        assertEquals(-4, frame.dy);
        assertTrue(frame.leftButtonDown);
    }

    @Test
    public void releaseUsesCanonicalBtnMouseCode() {
        LinuxInputFrameAccumulator accumulator = new LinuxInputFrameAccumulator();
        accumulator.accept(1, 0x110, 0);
        GetEventParser.Frame frame = accumulator.accept(0, 0, 0);
        assertFalse(frame.leftButtonDown);
    }

    @Test
    public void synDroppedDiscardsIncompleteFrame() {
        LinuxInputFrameAccumulator accumulator = new LinuxInputFrameAccumulator();
        accumulator.accept(2, 0, 50);
        accumulator.accept(0, 3, 0);
        assertNull(accumulator.accept(0, 0, 0));
    }
}
