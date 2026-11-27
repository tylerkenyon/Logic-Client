package dev.technix.logic.client.combat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class BacktrackHistory {
    public record Sample(long sequence, Vec3 position, AABB box) {}
    public record Selection(Sample sample, Vec3 hit) {}
    private final List<Sample> samples = new ArrayList<>();
    private double length;

    public void add(long sequence, Vec3 position, AABB box) {
        if (!samples.isEmpty()) length += samples.getLast().position().distanceTo(position);
        samples.add(new Sample(sequence, position, box));
    }

    public List<Sample> samples() { return List.copyOf(samples); }
    public double length() { return length; }

    public Selection select(Vec3 eye, Vec3 end, AABB latestBox) {
        if (intersect(latestBox, eye, end) == null) return null;
        Selection selected = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Sample sample : samples) {
            Vec3 hit = intersect(sample.box(), eye, end);
            if (hit == null) continue;
            double candidate = eye.distanceToSqr(hit);
            if (candidate < distance || candidate == distance && selected != null
                    && sample.sequence() > selected.sample().sequence()) {
                selected = new Selection(sample, hit);
                distance = candidate;
            }
        }
        return selected;
    }

    private static Vec3 intersect(AABB box, Vec3 eye, Vec3 end) {
        return box.contains(eye) ? eye : box.clip(eye, end).orElse(null);
    }

    public void releaseThrough(long sequence) {
        int anchor = 0;
        while (anchor + 1 < samples.size() && samples.get(anchor + 1).sequence() <= sequence) anchor++;
        samples.subList(0, anchor).clear();
        length = 0;
        for (int i = 1; i < samples.size(); i++)
            length += samples.get(i - 1).position().distanceTo(samples.get(i).position());
    }

    public void clear() { samples.clear(); length = 0; }
}
