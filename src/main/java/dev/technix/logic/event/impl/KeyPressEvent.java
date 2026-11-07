package dev.technix.logic.event.impl;

import dev.technix.logic.event.Event;

public class KeyPressEvent extends Event {

    private static final int GLFW_PRESS = 1;


    private int key;
    private final int action;


    public KeyPressEvent(int key, int action) {
        this.key = key;
        this.action = action;
    }

    public int key() {
        return key;
    }
    public int action() {
        return action;

    }
    public boolean isPress() {
        return GLFW_PRESS == action;
    }
}
