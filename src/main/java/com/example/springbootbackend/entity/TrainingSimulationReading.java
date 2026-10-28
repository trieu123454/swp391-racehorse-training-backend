package com.example.springbootbackend.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "training_simulation_readings")
public class TrainingSimulationReading {

    @EmbeddedId
    private TrainingSimulationReadingId id;

    @Column(name = "speed_kmh", nullable = false, precision = 6, scale = 3)
    private BigDecimal speedKmh;

    @Column(name = "heart_rate", nullable = false)
    private Integer heartRate;

    @Column(name = "bp_systolic", nullable = false)
    private Integer bpSystolic;

    @Column(name = "bp_diastolic", nullable = false)
    private Integer bpDiastolic;

    public TrainingSimulationReadingId getId() {
        return id;
    }

    public void setId(TrainingSimulationReadingId id) {
        this.id = id;
    }

    public BigDecimal getSpeedKmh() {
        return speedKmh;
    }

    public void setSpeedKmh(BigDecimal speedKmh) {
        this.speedKmh = speedKmh;
    }

    public Integer getHeartRate() {
        return heartRate;
    }

    public void setHeartRate(Integer heartRate) {
        this.heartRate = heartRate;
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
}
