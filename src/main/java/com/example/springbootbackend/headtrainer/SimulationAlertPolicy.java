package com.example.springbootbackend.headtrainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** BR-R04 warning thresholds for the training sensor simulation. */
@Component
public class SimulationAlertPolicy {
    private final int restingHeartRateBpm;
    private final int exerciseHeartRateBpm;
    private final int recoveryHeartRateBpm;
    private final int recoveryRestMinutes;
    private final int systolicBloodPressureMmhg;
    private final int highIntensityHeartRateBpm;
    private final int highIntensitySustainedSamples;

    public SimulationAlertPolicy(
            @Value("${app.head-trainer.simulation-alert.resting-heart-rate-bpm:60}") int restingHeartRateBpm,
            @Value("${app.head-trainer.simulation-alert.exercise-heart-rate-bpm:241}") int exerciseHeartRateBpm,
            @Value("${app.head-trainer.simulation-alert.recovery-heart-rate-bpm:64}") int recoveryHeartRateBpm,
            @Value("${app.head-trainer.simulation-alert.recovery-rest-minutes:15}") int recoveryRestMinutes,
            @Value("${app.head-trainer.simulation-alert.systolic-blood-pressure-mmhg:260}") int systolicBloodPressureMmhg,
            @Value("${app.head-trainer.simulation-alert.high-intensity-heart-rate-bpm:170}") int highIntensityHeartRateBpm,
            @Value("${app.head-trainer.simulation-alert.high-intensity-sustained-samples:2}") int highIntensitySustainedSamples) {
        this.restingHeartRateBpm = restingHeartRateBpm;
        this.exerciseHeartRateBpm = exerciseHeartRateBpm;
        this.recoveryHeartRateBpm = recoveryHeartRateBpm;
        this.recoveryRestMinutes = recoveryRestMinutes;
        this.systolicBloodPressureMmhg = systolicBloodPressureMmhg;
        this.highIntensityHeartRateBpm = highIntensityHeartRateBpm;
        this.highIntensitySustainedSamples = Math.max(2, highIntensitySustainedSamples);
    }

    public Map<String, Object> thresholds() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resting_heart_rate_bpm", restingHeartRateBpm);
        result.put("exercise_heart_rate_bpm", exerciseHeartRateBpm);
        result.put("recovery_heart_rate_bpm", recoveryHeartRateBpm);
        result.put("recovery_rest_minutes", recoveryRestMinutes);
        result.put("systolic_blood_pressure_mmhg", systolicBloodPressureMmhg);
        result.put("high_intensity_heart_rate_bpm", highIntensityHeartRateBpm);
        result.put("high_intensity_sustained_samples", highIntensitySustainedSamples);
        return result;
    }

    public Integer estimateRecoveryHeartRate(int restingHeartRate, int peakHeartRate, boolean fatigue) {
        int restSeconds = recoveryRestMinutes * 60;
        return RaceSimulationFormula.heartRate(restingHeartRate, peakHeartRate, restSeconds, 0, 1, fatigue);
    }

    public List<Map<String, Object>> evaluate(Map<String, Object> horse, List<Map<String, Object>> readings,
            Integer recoveryHeartRate, int samplingIntervalSeconds) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        int resting = number(horse, "base_resting_heart_rate");
        if (resting > restingHeartRateBpm) {
            alerts.add(alert("RESTING_HEART_RATE", "Nhịp tim lúc nghỉ", resting, restingHeartRateBpm,
                    "bpm", null, "Nhịp tim lúc nghỉ vượt ngưỡng mô phỏng."));
        }

        int maximumHeartRate = maximum(readings, "heart_rate");
        if (maximumHeartRate > exerciseHeartRateBpm) {
            alerts.add(alert("EXERCISE_HEART_RATE", "Nhịp tim lúc vận động", maximumHeartRate,
                    exerciseHeartRateBpm, "bpm", null, "Nhịp tim vận động vượt ngưỡng mô phỏng."));
        }

        int maximumSystolic = maximum(readings, "bp_systolic");
        if (maximumSystolic > systolicBloodPressureMmhg) {
            alerts.add(alert("SYSTOLIC_BLOOD_PRESSURE", "Huyết áp tâm thu", maximumSystolic,
                    systolicBloodPressureMmhg, "mmHg", null, "Huyết áp tâm thu vượt ngưỡng mô phỏng."));
        }

        Map<String, Object> sustained = sustainedHighIntensity(readings, samplingIntervalSeconds);
        if (sustained != null) alerts.add(sustained);

        if (recoveryHeartRate != null && recoveryHeartRate > recoveryHeartRateBpm) {
            alerts.add(alert("RECOVERY_HEART_RATE", "Nhịp tim phục hồi", recoveryHeartRate,
                    recoveryHeartRateBpm, "bpm", recoveryRestMinutes,
                    "Nhịp tim sau " + recoveryRestMinutes + " phút nghỉ vượt ngưỡng mô phỏng."));
        }
        return alerts;
    }

    private Map<String, Object> sustainedHighIntensity(List<Map<String, Object>> readings, int samplingIntervalSeconds) {
        int count = 0;
        int startSecond = 0;
        int previousSecond = -1;
        int maximumInRun = 0;
        int maxGap = Math.max(1, samplingIntervalSeconds);
        for (Map<String, Object> reading : readings) {
            int second = number(reading, "elapsed_seconds");
            int heartRate = number(reading, "heart_rate");
            boolean continuous = previousSecond >= 0 && second > previousSecond && second - previousSecond <= maxGap;
            if (heartRate > highIntensityHeartRateBpm) {
                if (!continuous || count == 0) {
                    count = 1;
                    startSecond = second;
                    maximumInRun = heartRate;
                } else {
                    count++;
                    maximumInRun = Math.max(maximumInRun, heartRate);
                }
                if (count >= highIntensitySustainedSamples) {
                    int durationSeconds = second - startSecond;
                    return alert("SUSTAINED_HIGH_INTENSITY", "Vùng vận động cường độ cao", maximumInRun,
                            highIntensityHeartRateBpm, "bpm", durationSeconds,
                            "Nhịp tim trên " + highIntensityHeartRateBpm + " bpm được duy trì liên tục trong mô phỏng.");
                }
            } else {
                count = 0;
                maximumInRun = 0;
            }
            previousSecond = second;
        }
        return null;
    }

    private Map<String, Object> alert(String code, String label, int value, int threshold, String unit,
            Integer durationSeconds, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("label", label);
        result.put("value", value);
        result.put("threshold", threshold);
        result.put("unit", unit);
        if (durationSeconds != null) result.put("duration_seconds", durationSeconds);
        result.put("message", message);
        return result;
    }

    private int maximum(List<Map<String, Object>> readings, String key) {
        int maximum = 0;
        for (Map<String, Object> reading : readings) maximum = Math.max(maximum, number(reading, key));
        return maximum;
    }

    private int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
