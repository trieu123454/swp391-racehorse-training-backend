package com.example.springbootbackend.headtrainer;

/** Deterministic workout model shared by production sampling and regression tests. */
public final class RaceSimulationFormula {
    private RaceSimulationFormula() {}

    public static int cadence(int duration) { return Math.max(5, (duration + 599) / 600); }

    public static double speedFactor(double progress) {
        double p = clamp(progress, 0, 1);
        if (p < 0.2) return 0.23 + 0.72 * p / 0.2;
        if (p < 0.7) return 0.95 + 0.03 * Math.sin((p - 0.2) / 0.5 * Math.PI);
        if (p < 0.9) return 0.95 * (1 - (p - 0.7) / 0.2);
        return 0;
    }

    public static double speedAt(double baseSpeed, double progress, int seed, int version) {
        if (progress >= 0.9) return 0;
        double noise = (noise(seed, progress, 0) - 0.5) * 0.03;
        double factor = version >= 3 ? speedFactor(progress) * (1 + noise) : speedFactor(progress) + noise;
        return Math.max(0, baseSpeed * factor);
    }

    public static double capacity(String intensity, String surface, double stamina) {
        // Peak training speeds in km/h: easy canter, brisk gallop, and race-speed gallop.
        double speed = switch (intensity) { case "Low" -> 22; case "High" -> 65; default -> 40; };
        double surfaceFactor = "Dirt".equals(surface) ? 0.94 : "Turf".equals(surface) ? 0.97 : 1;
        return speed * (0.9 + clamp(stamina, 0, 10) * 0.02) * surfaceFactor;
    }

    /**
     * Choose the shortest workout duration that can cover the target at the
     * intensity's speed capacity, while never exceeding the remaining calendar slot.
     */
    public static int durationForDistance(int distanceMeters, int availableSeconds, int seed, double capacity) {
        int high = Math.max(1, availableSeconds);
        if (distanceAtSpeed(capacity, high, seed) < distanceMeters) return high;

        int low = Math.min(60, high);
        if (distanceAtSpeed(capacity, low, seed) >= distanceMeters) return low;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (distanceAtSpeed(capacity, middle, seed) >= distanceMeters) high = middle;
            else low = middle + 1;
        }
        return low;
    }

    public static double baseSpeed(int distance, int duration, int seed, double capacity) {
        double metersPerUnit = distanceAtSpeed(1, duration, seed);
        return metersPerUnit > 0 ? Math.min(distance / metersPerUnit, capacity) : 0;
    }

    /** Uses the same cadence and trapezoidal integration as the persisted readings. */
    private static double distanceAtSpeed(double baseSpeed, int duration, int seed) {
        if (duration <= 0) return 0;
        double distance = 0;
        int previousSecond = 0;
        double previousSpeed = speedAt(baseSpeed, 0, seed, 3);
        for (int second = Math.min(cadence(duration), duration); ; second = Math.min(second + cadence(duration), duration)) {
            double speed = speedAt(baseSpeed, second / (double) duration, seed, 3);
            distance += (previousSpeed + speed) / 2 / 3.6 * (second - previousSecond);
            if (second == duration) break;
            previousSecond = second; previousSpeed = speed;
        }
        return distance;
    }

    public static int heartRate(double resting, double previous, int delta, double speed, double progress, boolean fatigue) {
        double effort = clamp(speed / 60, 0, 1);
        double demand = resting + 190 * effort * (1 + 0.12 * progress) * (fatigue ? 1.25 : 1);
        double response = demand > previous ? 35 : fatigue ? 140 : 90;
        return (int) Math.round(clamp(demand + (previous - demand) * Math.exp(-delta / response), resting, 250));
    }

    public static double noise(int seed, double progress, int salt) {
        int sample = (int) Math.floor(clamp(progress, 0, 1) * 1000);
        int value = seed ^ salt ^ (sample * 0x45d9f3b);
        value ^= value >>> 16;
        value *= 0x45d9f3b;
        value ^= value >>> 16;
        return (value & 0x7fffffff) / (double) Integer.MAX_VALUE;
    }

    public static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
