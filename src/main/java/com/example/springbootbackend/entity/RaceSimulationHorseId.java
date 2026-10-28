package com.example.springbootbackend.entity;

import java.io.Serializable;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class RaceSimulationHorseId implements Serializable {

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "simulation_id", nullable = false, length = 36)
    private String simulationId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "horse_id", nullable = false, length = 36)
    private String horseId;

    public RaceSimulationHorseId() {
    }

    public RaceSimulationHorseId(String simulationId, String horseId) {
        this.simulationId = simulationId;
        this.horseId = horseId;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RaceSimulationHorseId that)) return false;
        return Objects.equals(simulationId, that.simulationId) && Objects.equals(horseId, that.horseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(simulationId, horseId);
    }
}
