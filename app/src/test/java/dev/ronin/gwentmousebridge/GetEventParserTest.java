package dev.ronin.gwentmousebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class GetEventParserTest {
    @Test
    public void btnMouseDownUpProducesTap() {
        assertTextButtonProducesTap("BTN_MOUSE");
    }

    @Test
    public void btnLeftAliasDownUpProducesTap() {
        assertTextButtonProducesTap("BTN_LEFT");
    }

    @Test
    public void numeric0110DownUpProducesTap() {
        GetEventParser parser = new GetEventParser();
        MouseGestureStateMachine state = new MouseGestureStateMachine();

        assertNull(parser.accept("[1.0] /dev/input/event14: 0001 0110 00000001"));
        GetEventParser.Frame down = parser.accept("[1.0] /dev/input/event14: 0000 0000 00000000");
        assertEquals(
                List.of(MouseGestureStateMachine.Action.PRESS),
                state.onFrame(false, buttonState(down), true));

        parser.accept("[1.1] /dev/input/event14: 0001 0110 00000000");
        GetEventParser.Frame up = parser.accept("[1.1] /dev/input/event14: 0000 0000 00000000");
        assertEquals(
                List.of(MouseGestureStateMachine.Action.TAP),
                state.onFrame(false, buttonState(up), true));
    }

    @Test
    public void downAndXyInOneSynFrameProducesOneDragStartAndNoTap() {
        GetEventParser parser = new GetEventParser();
        MouseGestureStateMachine state = new MouseGestureStateMachine();

        assertNull(parser.accept("EV_KEY BTN_MOUSE DOWN"));
        assertNull(parser.accept("EV_REL REL_X 00000008"));
        assertNull(parser.accept("EV_REL REL_Y fffffffc"));
        GetEventParser.Frame frame = parser.accept("EV_SYN SYN_REPORT 00000000");

        assertEquals(8, frame.dx);
        assertEquals(-4, frame.dy);
        assertEquals(
                List.of(
                        MouseGestureStateMachine.Action.PRESS,
                        MouseGestureStateMachine.Action.DRAG_START),
                state.onFrame(true, buttonState(frame), true));

        parser.accept("EV_KEY BTN_MOUSE UP");
        GetEventParser.Frame up = parser.accept("EV_SYN SYN_REPORT 00000000");
        List<MouseGestureStateMachine.Action> actions = state.onFrame(false, buttonState(up), true);
        assertEquals(List.of(MouseGestureStateMachine.Action.DRAG_END), actions);
        assertFalse(actions.contains(MouseGestureStateMachine.Action.TAP));
    }

    @Test
    public void xAndYAreEmittedAsOnePointerUpdateAtSynReport() {
        GetEventParser parser = new GetEventParser();
        assertNull(parser.accept("EV_REL REL_X 00000002"));
        assertNull(parser.accept("EV_REL REL_Y 00000003"));

        GetEventParser.Frame frame = parser.accept("EV_SYN SYN_REPORT 00000000");
        assertEquals(2, frame.dx);
        assertEquals(3, frame.dy);
        assertNull(frame.leftButtonDown);
        assertNull(parser.accept("EV_SYN SYN_REPORT 00000000"));
    }

    @Test
    public void relativeHexValuesAreParsedAsSignedAndAccumulated() {
        GetEventParser parser = new GetEventParser();
        assertNull(parser.accept("EV_REL REL_X fffffffe"));
        assertNull(parser.accept("EV_REL REL_X 00000001"));
        assertNull(parser.accept("EV_REL REL_Y fffffffd"));

        GetEventParser.Frame frame = parser.accept("EV_SYN SYN_REPORT 00000000");
        assertEquals(-1, frame.dx);
        assertEquals(-3, frame.dy);
    }

    private static void assertTextButtonProducesTap(String alias) {
        GetEventParser parser = new GetEventParser();
        MouseGestureStateMachine state = new MouseGestureStateMachine();

        parser.accept("[1.0] /dev/input/event14: EV_KEY " + alias + " DOWN");
        GetEventParser.Frame down = parser.accept("EV_SYN SYN_REPORT 00000000");
        assertTrue(down.leftButtonDown);
        assertEquals(
                List.of(MouseGestureStateMachine.Action.PRESS),
                state.onFrame(false, buttonState(down), true));

        parser.accept("[1.1] /dev/input/event14: EV_KEY " + alias + " UP");
        GetEventParser.Frame up = parser.accept("EV_SYN SYN_REPORT 00000000");
        assertFalse(up.leftButtonDown);
        assertEquals(
                List.of(MouseGestureStateMachine.Action.TAP),
                state.onFrame(false, buttonState(up), true));
    }

    private static int buttonState(GetEventParser.Frame frame) {
        if (frame.leftButtonDown == null) return MouseGestureStateMachine.BUTTON_UNCHANGED;
        return frame.leftButtonDown
                ? MouseGestureStateMachine.BUTTON_DOWN
                : MouseGestureStateMachine.BUTTON_UP;
    }
}
