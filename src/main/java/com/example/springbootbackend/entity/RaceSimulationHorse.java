package com.example.springbootbackend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "race_simulation_horses")
public class RaceSimulationHorse {

    @EmbeddedId
    private RaceSimulationHorseId id;

    @Column(name = "lane", nullable = false)
    private Integer lane;

    @Column(name = "seed", nullable = false)
    private Integer seed;

    @Column(name = "base_max_speed_kmh", nullable = false, precision = 8, scale = 4)
    private BigDecimal baseMaxSpeedKmh;

    @Column(name = "base_resting_heart_rate", nullable = false)
    private Integer baseRestingHeartRate;

    @Column(name = "base_bp_systolic", nullable = false)
    private Integer baseBpSystolic;

    @Column(name = "base_bp_diastolic", nullable = false)
    private Integer baseBpDiastolic;

    @Column(name = "baseline_stamina_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal baselineStaminaScore = new BigDecimal("5");

    @Column(name = "body_weight_kg", precision = 6, scale = 2)
    private BigDecimal bodyWeightKg;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "metric_id", length = 36)
    private String metricId;

    @Column(name = "finish_time_seconds", precision = 10, scale = 2)
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

    @Column(name = "metric_created_at")
    private LocalDateTime metricCreatedAt;

    public RaceSimulationHorseId getId() {
        return id;
    }

    public void setId(RaceSimulationHorseId id) {
        this.id = id;
    }

    public Integer getLane() {
        return lane;
    }

    public void setLane(Integer lane) {
        this.lane = lane;
    }

    public Integer getSeed() {
        return seed;
    }

    public void setSeed(Integer seed) {
        this.seed = seed;
    }

    public BigDecimal getBaseMaxSpeedKmh() {
        return baseMaxSpeedKmh;
    }

    public void setBaseMaxSpeedKmh(BigDecimal baseMaxSpeedKmh) {
        this.baseMaxSpeedKmh = baseMaxSpeedKmh;
    }

    public Integer getBaseRestingHeartRate() {
        return baseRestingHeartRate;
    }

    public void setBaseRestingHeartRate(Integer baseRestingHeartRate) {
        this.baseRestingHeartRate = baseRestingHeartRate;
    }

    public Integer getBaseBpSystolic() {
        return baseBpSystolic;
    }

    public void setBaseBpSystolic(Integer baseBpSystolic) {
        this.baseBpSystolic = baseBpSystolic;
    }

    public Integer getBaseBpDiastolic() {
        return baseBpDiastolic;
    }

    public void setBaseBpDiastolic(Integer baseBpDiastolic) {
        this.baseBpDiastolic = baseBpDiastolic;
    }

    public BigDecimal getBaselineStaminaScore() {
        return baselineStaminaScore;
    }

    public void setBaselineStaminaScore(BigDecimal baselineStaminaScore) {
        this.baselineStaminaScore = baselineStaminaScore;
    }

    public BigDecimal getBodyWeightKg() {
        return bodyWeightKg;
    }

    public void setBodyWeightKg(BigDecimal bodyWeightKg) {
        this.bodyWeightKg = bodyWeightKg;
    }

    public String getMetricId() {
        return metricId;
    }

    public void setMetricId(String metricId) {
        this.metricId = metricId;
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

    public LocalDateTime getMetricCreatedAt() {
        return metricCreatedAt;
    }

    public void setMetricCreatedAt(LocalDateTime metricCreatedAt) {
        this.metricCreatedAt = metricCreatedAt;
    }
}
