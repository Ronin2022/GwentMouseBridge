package dev.ronin.gwentmousebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GetEventParserTest {
    @Test
    public void movementIsCoalescedAndSignedPerSynFrame() {
        GetEventParser parser = new GetEventParser();
        assertNull(parser.accept("EV_REL REL_X fffffffe"));
        assertNull(parser.accept("EV_REL REL_X 00000001"));
        assertNull(parser.accept("EV_REL REL_Y 00000003"));

        GetEventParser.Frame frame = parser.accept("EV_SYN SYN_REPORT 00000000");
        assertEquals(-1, frame.dx);
        assertEquals(3, frame.dy);
        assertNull(frame.leftButtonDown);
    }

    @Test
    public void leftButtonDownAndUpAreReported() {
        GetEventParser parser = new GetEventParser();
        parser.accept("EV_KEY BTN_LEFT DOWN");
        GetEventParser.Frame down = parser.accept("EV_SYN SYN_REPORT 00000000");
        assertTrue(down.leftButtonDown);

        parser.accept("EV_KEY BTN_LEFT UP");
        GetEventParser.Frame up = parser.accept("EV_SYN SYN_REPORT 00000000");
        assertFalse(up.leftButtonDown);
    }

    @Test
    public void emptySynFrameProducesNoCallback() {
        assertNull(new GetEventParser().accept("EV_SYN SYN_REPORT 00000000"));
    }
}
