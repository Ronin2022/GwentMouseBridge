package dev.ronin.gwentmousebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class MouseGestureStateMachineTest {
    @Test
    public void multipleMovementFramesProduceStartUpdatesAndEndWithoutTap() {
        MouseGestureStateMachine state = new MouseGestureStateMachine();
        List<MouseGestureStateMachine.Action> actions = new ArrayList<>();

        actions.addAll(state.onFrame(false, MouseGestureStateMachine.BUTTON_DOWN, true));
        actions.addAll(state.onFrame(true, MouseGestureStateMachine.BUTTON_UNCHANGED, true));
        actions.addAll(state.onFrame(true, MouseGestureStateMachine.BUTTON_UNCHANGED, true));
        actions.addAll(state.onFrame(true, MouseGestureStateMachine.BUTTON_UNCHANGED, true));
        actions.addAll(state.onFrame(false, MouseGestureStateMachine.BUTTON_UP, true));

        assertEquals(
                List.of(
                        MouseGestureStateMachine.Action.PRESS,
                        MouseGestureStateMachine.Action.DRAG_START,
                        MouseGestureStateMachine.Action.DRAG_UPDATE,
                        MouseGestureStateMachine.Action.DRAG_UPDATE,
                        MouseGestureStateMachine.Action.DRAG_END),
                actions);
        assertFalse(actions.contains(MouseGestureStateMachine.Action.TAP));
    }

    @Test
    public void targetNotForegroundProducesNoInjectionActions() {
        MouseGestureStateMachine state = new MouseGestureStateMachine();
        assertTrue(state.onFrame(false, MouseGestureStateMachine.BUTTON_DOWN, false).isEmpty());
        assertTrue(state.onFrame(true, MouseGestureStateMachine.BUTTON_UNCHANGED, false).isEmpty());
        assertTrue(state.onFrame(false, MouseGestureStateMachine.BUTTON_UP, true).isEmpty());
    }

    @Test
    public void foregroundLossDuringDragAbortsAndReleaseCannotTap() {
        MouseGestureStateMachine state = new MouseGestureStateMachine();
        state.onFrame(false, MouseGestureStateMachine.BUTTON_DOWN, true);
        state.onFrame(true, MouseGestureStateMachine.BUTTON_UNCHANGED, true);

        assertEquals(
                List.of(MouseGestureStateMachine.Action.ABORT),
                state.onFrame(false, MouseGestureStateMachine.BUTTON_UNCHANGED, false));
        assertTrue(state.onFrame(false, MouseGestureStateMachine.BUTTON_UP, true).isEmpty());
    }

    @Test
    public void binderDeathAbortLeavesBridgePassive() {
        MouseGestureStateMachine state = new MouseGestureStateMachine();
        state.onFrame(false, MouseGestureStateMachine.BUTTON_DOWN, true);
        assertEquals(List.of(MouseGestureStateMachine.Action.ABORT), state.abort());

        assertTrue(state.onFrame(true, MouseGestureStateMachine.BUTTON_UNCHANGED, true).isEmpty());
        assertTrue(state.onFrame(false, MouseGestureStateMachine.BUTTON_UP, true).isEmpty());
    }
}
