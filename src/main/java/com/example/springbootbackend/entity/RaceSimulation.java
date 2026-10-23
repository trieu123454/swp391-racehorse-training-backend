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
@Table(name = "race_simulations")
public class RaceSimulation {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "distance_meters", nullable = false)
    private Integer distanceMeters;

    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "training_schedule_id", length = 36)
    private String trainingScheduleId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "race_id", unique = true, length = 36)
    private String raceId;

    @Column(name = "target_intensity", length = 20)
    private String targetIntensity;

    @Column(name = "track_surface", length = 50)
    private String trackSurface;

    @Column(name = "actual_distance_meters", precision = 12, scale = 2)
    private BigDecimal actualDistanceMeters;

    @Column(name = "model_version", nullable = false)
    private Integer modelVersion = 1;

    @Column(name = "stop_reason", length = 255)
    private String stopReason;

    @Column(name = "recovery_heart_rate")
    private Integer recoveryHeartRate;

    @Column(name = "stamina_score", precision = 5, scale = 2)
    private BigDecimal staminaScore;

    @Column(name = "scenario", nullable = false, length = 20)
    private String scenario = "Normal";

    @Column(name = "status", nullable = false, length = 20)
    private String status = "Running";

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "stopped_at")
    private LocalDateTime stoppedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(Integer distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getTrainingScheduleId() {
        return trainingScheduleId;
    }

    public void setTrainingScheduleId(String trainingScheduleId) {
        this.trainingScheduleId = trainingScheduleId;
    }

    public String getRaceId() {
        return raceId;
    }

    public void setRaceId(String raceId) {
        this.raceId = raceId;
    }

    public String getTargetIntensity() {
        return targetIntensity;
    }

    public void setTargetIntensity(String targetIntensity) {
        this.targetIntensity = targetIntensity;
    }

    public String getTrackSurface() {
        return trackSurface;
    }

    public void setTrackSurface(String trackSurface) {
        this.trackSurface = trackSurface;
    }

    public BigDecimal getActualDistanceMeters() {
        return actualDistanceMeters;
    }

    public void setActualDistanceMeters(BigDecimal actualDistanceMeters) {
        this.actualDistanceMeters = actualDistanceMeters;
    }

    public Integer getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(Integer modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public Integer getRecoveryHeartRate() {
        return recoveryHeartRate;
    }

    public void setRecoveryHeartRate(Integer recoveryHeartRate) {
        this.recoveryHeartRate = recoveryHeartRate;
    }

    public BigDecimal getStaminaScore() {
        return staminaScore;
    }

    public void setStaminaScore(BigDecimal staminaScore) {
        this.staminaScore = staminaScore;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStoppedAt() {
        return stoppedAt;
    }

    public void setStoppedAt(LocalDateTime stoppedAt) {
        this.stoppedAt = stoppedAt;
    }
}
