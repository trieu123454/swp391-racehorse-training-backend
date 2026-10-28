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
@Table(name = "race_simulation_metrics")
public class RaceSimulationMetric {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "simulation_id", nullable = false, length = 36)
    private String simulationId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "horse_id", nullable = false, length = 36)
    private String horseId;

    @Column(name = "lane")
    private Integer lane;

    @Column(name = "finish_time_seconds", nullable = false, precision = 10, scale = 2)
    private BigDecimal finishTimeSeconds;

    @Column(name = "avg_speed_kmh", precision = 5, scale = 2)
    private BigDecimal avgSpeedKmh;

    @Column(name = "max_heart_rate")
    private Integer maxHeartRate;

    @Column(name = "max_bp_systolic")
    private Integer maxBpSystolic;

    @Column(name = "max_bp_diastolic")
    private Integer maxBpDiastolic;

    @Column(name = "rank")
    private Integer rank;

    @Column(name = "has_injury_alert", nullable = false)
    private boolean hasInjuryAlert = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSimulationId() {
        return simulationId;
    }

    public void setSimulationId(String simulationId) {
        this.simulationId = simulationId;
    }

    public String getHorseId() {
        return horseId;
    }

    public void setHorseId(String horseId) {
        this.horseId = horseId;
    }

    public Integer getLane() {
        return lane;
    }

    public void setLane(Integer lane) {
        this.lane = lane;
    }

    public BigDecimal getFinishTimeSeconds() {
        return finishTimeSeconds;
    }

    public void setFinishTimeSeconds(BigDecimal finishTimeSeconds) {
        this.finishTimeSeconds = finishTimeSeconds;
    }

    public BigDecimal getAvgSpeedKmh() {
        return avgSpeedKmh;
    }

    public void setAvgSpeedKmh(BigDecimal avgSpeedKmh) {
        this.avgSpeedKmh = avgSpeedKmh;
    }

    public Integer getMaxHeartRate() {
        return maxHeartRate;
    }

    public void setMaxHeartRate(Integer maxHeartRate) {
        this.maxHeartRate = maxHeartRate;
    }

    public Integer getMaxBpSystolic() {
        return maxBpSystolic;
    }

    public void setMaxBpSystolic(Integer maxBpSystolic) {
        this.maxBpSystolic = maxBpSystolic;
    }

    public Integer getMaxBpDiastolic() {
        return maxBpDiastolic;
    }

    public void setMaxBpDiastolic(Integer maxBpDiastolic) {
        this.maxBpDiastolic = maxBpDiastolic;
    }

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public boolean isHasInjuryAlert() {
        return hasInjuryAlert;
    }

    public void setHasInjuryAlert(boolean hasInjuryAlert) {
        this.hasInjuryAlert = hasInjuryAlert;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
