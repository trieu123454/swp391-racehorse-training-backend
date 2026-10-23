package com.example.springbootbackend.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RaceSimulationFormulaTests {
    @Test
    void speedProfileAcceleratesCruisesAndEasesNearFinish() {
        assertThat(RaceSimulationFormula.speedFactor(0.15)).isGreaterThan(RaceSimulationFormula.speedFactor(0));
        assertThat(RaceSimulationFormula.speedFactor(0.55)).isGreaterThan(RaceSimulationFormula.speedFactor(0.95));
        assertThat(RaceSimulationFormula.speedAt(48, 0.5, 483920, 3)).isEqualTo(RaceSimulationFormula.speedAt(48, 0.5, 483920, 3));
        assertThat(RaceSimulationFormula.speedAt(48, 0.95, 483920, 3)).isZero();
    }

    @Test
    void heartRateRespondsToLoadAndRecoversMoreSlowlyWithFatigue() {
        int loaded = RaceSimulationFormula.heartRate(34, 34, 180, 48, 0.6, true);
        assertThat(loaded).isBetween(221, 250);
        int normal = RaceSimulationFormula.heartRate(34, loaded, 90, 0, 0.95, false);
        int fatigue = RaceSimulationFormula.heartRate(34, loaded, 90, 0, 0.95, true);
        assertThat(normal).isLessThan(fatigue).isGreaterThan(34);
        assertThat(fatigue).isLessThan(loaded);
    }

    @Test
    void slowLongWorkoutsFollowDistanceAndImpossibleTargetsRemainShortfalls() {
        for (int duration : new int[]{3600, 3601, 7200}) {
            double base = RaceSimulationFormula.baseSpeed(1200, duration, 483920, 28);
            assertThat(base).isLessThan(6);
            double distance = integrate(base, duration, 483920);
            assertThat(distance).isCloseTo(1200, org.assertj.core.data.Offset.offset(0.001));
        }
        double base = RaceSimulationFormula.baseSpeed(50000, 600, 7, 16);
        assertThat(base).isEqualTo(16);
        assertThat(integrate(base, 600, 7)).isLessThan(50000);
    }

    private double integrate(double base, int duration, int seed) {
        double distance = 0, previousSpeed = RaceSimulationFormula.speedAt(base, 0, seed, 3);
        int previousSecond = 0;
        for (int second = Math.min(RaceSimulationFormula.cadence(duration), duration);; second = Math.min(second + RaceSimulationFormula.cadence(duration), duration)) {
            double speed = RaceSimulationFormula.speedAt(base, second / (double) duration, seed, 3);
            distance += (previousSpeed + speed) / 2 / 3.6 * (second - previousSecond);
            if (second == duration) return distance;
            previousSpeed = speed; previousSecond = second;
        }
    }
}
