package dev.technix.logic.client.combat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

 
public final class PacketDelayQueue<T> {
    private record Entry<T>(T value, long arrival, long tick) { }
    private final ArrayDeque<Entry<T>> entries = new ArrayDeque<>();
    private final int capacity;

    public PacketDelayQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        this.capacity = capacity;
    }

    public boolean offer(T value, long now) {
        return offer(value, now, now);
    }

    public boolean offer(T value, long now, long tick) {
        if (entries.size() >= capacity) return false;
        entries.addLast(new Entry<>(value, now, tick));
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
        long first = entries.getFirst().tick();
        while (!entries.isEmpty() && entries.getFirst().tick() - first < span) {
            result.add(entries.removeFirst().value());
        }
        return result;
    }

    public List<T> drain() {
        List<T> result = new ArrayList<>();
        while (!entries.isEmpty()) result.add(entries.removeFirst().value());
        return result;
    }

    public List<T> releaseThrough(long arrival) {
        List<T> ready = new ArrayList<>();
        while (!entries.isEmpty() && entries.getFirst().arrival() <= arrival) {
            ready.add(entries.removeFirst().value());
        }
        return ready;
    }

    public void clear() { entries.clear(); }
    public int size() { return entries.size(); }
}
