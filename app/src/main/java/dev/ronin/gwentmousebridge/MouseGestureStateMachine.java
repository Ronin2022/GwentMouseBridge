package dev.ronin.gwentmousebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Converts atomic mouse frames into mutually exclusive tap, drag, or abort actions. */
final class MouseGestureStateMachine {
    static final int BUTTON_UNCHANGED = -1;
    static final int BUTTON_UP = 0;
    static final int BUTTON_DOWN = 1;

    enum Action {
        PRESS,
        TAP,
        DRAG_START,
        DRAG_UPDATE,
        DRAG_END,
        ABORT
    }

    private boolean buttonDown;
    private boolean pressActive;
    private boolean dragging;

    List<Action> onFrame(boolean moved, int buttonState, boolean injectionAllowed) {
        if (!injectionAllowed) return abort();

        List<Action> actions = new ArrayList<>(3);
        if (buttonState == BUTTON_DOWN && !buttonDown) {
            buttonDown = true;
            pressActive = true;
            dragging = false;
            actions.add(Action.PRESS);
        }

        if (moved && buttonDown && pressActive) {
            if (dragging) {
                actions.add(Action.DRAG_UPDATE);
            } else {
                dragging = true;
                actions.add(Action.DRAG_START);
            }
        }

        if (buttonState == BUTTON_UP) {
            if (buttonDown && pressActive) {
                actions.add(dragging ? Action.DRAG_END : Action.TAP);
            }
            clear();
        }
        return actions;
    }

    List<Action> abort() {
        if (!pressActive) {
            clear();
            return Collections.emptyList();
        }
        clear();
        return Collections.singletonList(Action.ABORT);
    }

    void reset() {
        clear();
    }

    private void clear() {
        buttonDown = false;
        pressActive = false;
        dragging = false;
    }
}
