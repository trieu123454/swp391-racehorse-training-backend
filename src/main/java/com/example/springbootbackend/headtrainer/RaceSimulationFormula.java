package com.example.springbootbackend.headtrainer;

/** Stable formula constants mirrored by the frontend simulator module. */
public final class RaceSimulationFormula {
    private RaceSimulationFormula() {}

    public static double speedFactor(double progress) {
        double p = Math.max(0, Math.min(1, progress));
        if (p < 0.15) {
            double x = p / 0.15;
            double eased = x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
            return 0.93 * eased;
        }
        if (p < 0.8) return 0.94 + 0.035 * Math.sin((p - 0.15) / 0.65 * Math.PI);
        return 0.975 - 0.105 * ((p - 0.8) / 0.2);
    }

    public static double speedAt(double baseMaxSpeedKmh, double progress, int seed) {
        double factor = speedFactor(progress);
        double noise = (unitNoise(seed, progress) - 0.5) * 0.03;
        return Math.max(0, baseMaxSpeedKmh * (factor + noise));
    }

    public static int heartRateAt(int baseRestingHeartRate, double speedKmh,
            double baseMaxSpeedKmh, double progress, int seed) {
        double ratio = baseMaxSpeedKmh <= 0 ? 0 : speedKmh / baseMaxSpeedKmh;
        double noise = (unitNoise(seed ^ 0x4f1bbcdc, progress) - 0.5) * 7;
        return (int) Math.round(Math.max(baseRestingHeartRate,
                Math.min(230, baseRestingHeartRate + (220 - baseRestingHeartRate) * ratio * 0.7 + noise)));
    }

    public static int systolicAt(int baseSystolic, double speedKmh,
            double baseMaxSpeedKmh, double progress, int seed) {
        double ratio = baseMaxSpeedKmh <= 0 ? 0 : speedKmh / baseMaxSpeedKmh;
        double noise = (unitNoise(seed ^ 0x7a143589, progress) - 0.5) * 8;
        return (int) Math.round(Math.max(40, Math.min(300, baseSystolic + ratio * 40 + noise)));
    }

    public static int diastolicAt(int baseDiastolic, double progress, int seed) {
        double gentleWave = 3 * Math.sin(Math.max(0, Math.min(1, progress)) * Math.PI * 2);
        double noise = (unitNoise(seed ^ 0x1b873593, progress) - 0.5) * 6;
        return (int) Math.round(Math.max(60, Math.min(90, baseDiastolic + gentleWave + noise)));
    }

    private static double unitNoise(int seed, double progress) {
        int sample = Math.max(0, Math.min(10000, (int) Math.floor(progress * 1000)));
        int value = seed ^ (sample * 0x45d9f3b);
        value ^= value >>> 16;
        value *= 0x45d9f3b;
        value ^= value >>> 16;
        return (value & 0x7fffffff) / (double) Integer.MAX_VALUE;
    }
}
