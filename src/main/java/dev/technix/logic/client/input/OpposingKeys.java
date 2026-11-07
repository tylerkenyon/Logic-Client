package dev.technix.logic.client.input;

 
public final class OpposingKeys {
    private boolean first, second;
    private int last;

    public void update(boolean first, boolean second) {
        boolean firstPressed = first && !this.first, secondPressed = second && !this.second;
        if (firstPressed && secondPressed) last = 0;
        else if (firstPressed) last = -1;
        else if (secondPressed) last = 1;
        this.first = first; this.second = second;
    }
    public boolean firstActive() { return first && (!second || last < 0); }
    public boolean secondActive() { return second && (!first || last > 0); }
    public void reset() { first = second = false; last = 0; }
}
