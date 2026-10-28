package com.example.springbootbackend.entity;

import java.math.BigDecimal;

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
}
