package com.example.springbootbackend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "training_metrics_logs")
public class TrainingMetricsLog {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "horse_id", nullable = false, length = 36)
    private String horseId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "training_schedule_id", length = 36)
    private String trainingScheduleId;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @Column(name = "body_weight_kg", precision = 6, scale = 2)
    private BigDecimal bodyWeightKg;

    @Column(name = "max_heart_rate")
    private Integer maxHeartRate;

    @Column(name = "avg_speed_kmh", precision = 5, scale = 2)
    private BigDecimal avgSpeedKmh;

    @Column(name = "stamina_score", precision = 5, scale = 2)
    private BigDecimal staminaScore;

    @Column(name = "has_injury_alert", nullable = false)
    private boolean hasInjuryAlert = false;

    @Column(name = "trainer_review", columnDefinition = "TEXT")
    private String trainerReview;

    @Column(name = "bp_systolic")
    private Integer bpSystolic;

    @Column(name = "bp_diastolic")
    private Integer bpDiastolic;

    @Column(name = "is_simulated", nullable = false)
    private boolean simulated = false;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHorseId() {
        return horseId;
    }

    public void setHorseId(String horseId) {
        this.horseId = horseId;
    }

    public String getTrainingScheduleId() {
        return trainingScheduleId;
    }

    public void setTrainingScheduleId(String trainingScheduleId) {
        this.trainingScheduleId = trainingScheduleId;
    }

    public Long getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(Long recordedBy) {
        this.recordedBy = recordedBy;
    }

    public BigDecimal getBodyWeightKg() {
        return bodyWeightKg;
    }

    public void setBodyWeightKg(BigDecimal bodyWeightKg) {
        this.bodyWeightKg = bodyWeightKg;
    }

    public Integer getMaxHeartRate() {
        return maxHeartRate;
    }

    public void setMaxHeartRate(Integer maxHeartRate) {
        this.maxHeartRate = maxHeartRate;
    }

    public BigDecimal getAvgSpeedKmh() {
        return avgSpeedKmh;
    }

    public void setAvgSpeedKmh(BigDecimal avgSpeedKmh) {
        this.avgSpeedKmh = avgSpeedKmh;
    }

    public BigDecimal getStaminaScore() {
        return staminaScore;
    }

    public void setStaminaScore(BigDecimal staminaScore) {
        this.staminaScore = staminaScore;
    }

    public boolean isHasInjuryAlert() {
        return hasInjuryAlert;
    }

    public void setHasInjuryAlert(boolean hasInjuryAlert) {
        this.hasInjuryAlert = hasInjuryAlert;
    }

    public String getTrainerReview() {
        return trainerReview;
    }

    public void setTrainerReview(String trainerReview) {
        this.trainerReview = trainerReview;
    }

    public Integer getBpSystolic() {
        return bpSystolic;
    }

    public void setBpSystolic(Integer bpSystolic) {
        this.bpSystolic = bpSystolic;
    }

    public Integer getBpDiastolic() {
        return bpDiastolic;
    }

    public void setBpDiastolic(Integer bpDiastolic) {
        this.bpDiastolic = bpDiastolic;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public void setSimulated(boolean simulated) {
        this.simulated = simulated;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }
}
