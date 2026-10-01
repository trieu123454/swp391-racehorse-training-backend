package com.example.springbootbackend.headtrainer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RaceSimulationFormulaTests {
    @Test
    void speedProfileAcceleratesCruisesAndEasesNearFinish() {
        assertThat(RaceSimulationFormula.speedFactor(0.15)).isGreaterThan(RaceSimulationFormula.speedFactor(0));
        assertThat(RaceSimulationFormula.speedFactor(0.55)).isGreaterThan(RaceSimulationFormula.speedFactor(0.95));
        assertThat(RaceSimulationFormula.speedAt(58, 0.5, 483920)).isEqualTo(RaceSimulationFormula.speedAt(58, 0.5, 483920));
    }

    @Test
    void generatedVitalsStayWithinHorseDemoRanges() {
        double speed = RaceSimulationFormula.speedAt(58.4, 0.63, 483920);
        int heartRate = RaceSimulationFormula.heartRateAt(34, speed, 58.4, 0.63, 483920);
        int systolic = RaceSimulationFormula.systolicAt(110, speed, 58.4, 0.63, 483920);
        int diastolic = RaceSimulationFormula.diastolicAt(70, 0.63, 483920);
        assertThat(speed).isBetween(0.0, 65.0);
        assertThat(heartRate).isBetween(34, 230);
        assertThat(systolic).isBetween(40, 300);
        assertThat(diastolic).isBetween(60, 90);
    }
}
