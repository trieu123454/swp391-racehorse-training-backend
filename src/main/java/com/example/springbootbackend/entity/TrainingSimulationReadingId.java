package com.example.springbootbackend.entity;

import java.io.Serializable;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class TrainingSimulationReadingId implements Serializable {

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "simulation_id", nullable = false, length = 36)
    private String simulationId;

    @Column(name = "elapsed_seconds", nullable = false)
    private Integer elapsedSeconds;

    public TrainingSimulationReadingId() {
    }

    public TrainingSimulationReadingId(String simulationId, Integer elapsedSeconds) {
        this.simulationId = simulationId;
        this.elapsedSeconds = elapsedSeconds;
    }

    public String getSimulationId() {
        return simulationId;
    }

    public void setSimulationId(String simulationId) {
        this.simulationId = simulationId;
    }

    public Integer getElapsedSeconds() {
        return elapsedSeconds;
    }

    public void setElapsedSeconds(Integer elapsedSeconds) {
        this.elapsedSeconds = elapsedSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrainingSimulationReadingId that)) return false;
        return Objects.equals(simulationId, that.simulationId) && Objects.equals(elapsedSeconds, that.elapsedSeconds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(simulationId, elapsedSeconds);
    }
}
