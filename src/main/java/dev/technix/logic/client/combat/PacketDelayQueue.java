package dev.technix.logic.client.combat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

 
public final class PacketDelayQueue<T> {
    private record Entry<T>(T value, long arrival) { }
    private final ArrayDeque<Entry<T>> entries = new ArrayDeque<>();
    private final int capacity;

    public PacketDelayQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        this.capacity = capacity;
    }

    public boolean offer(T value, long now) {
        if (entries.size() >= capacity) return false;
        entries.addLast(new Entry<>(value, now));
        return true;
    }

    public List<T> release(long now, long delayNanos) {
        List<T> ready = new ArrayList<>();
        while (!entries.isEmpty() && now - entries.getFirst().arrival() >= delayNanos) {
            ready.add(entries.removeFirst().value());
        }
        return ready;
    }

    public List<T> releaseSpan(long span) {
        List<T> result = new ArrayList<>();
        if (span <= 0 || entries.isEmpty()) return result;
        long first = entries.getFirst().arrival();
        while (!entries.isEmpty() && entries.getFirst().arrival() - first < span) {
            result.add(entries.removeFirst().value());
        }
        return result;
    }

    public List<T> drain() {
        List<T> result = new ArrayList<>();
        while (!entries.isEmpty()) result.add(entries.removeFirst().value());
        return result;
    }

    public void clear() { entries.clear(); }
    public int size() { return entries.size(); }
}
