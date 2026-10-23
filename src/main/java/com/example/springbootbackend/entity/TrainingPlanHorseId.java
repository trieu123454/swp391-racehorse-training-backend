package com.example.springbootbackend.entity;

import java.io.Serializable;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class TrainingPlanHorseId implements Serializable {

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "training_plan_id", nullable = false, length = 36)
    private String trainingPlanId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "horse_id", nullable = false, length = 36)
    private String horseId;

    public TrainingPlanHorseId() {
    }

    public TrainingPlanHorseId(String trainingPlanId, String horseId) {
        this.trainingPlanId = trainingPlanId;
        this.horseId = horseId;
    }

    public String getTrainingPlanId() {
        return trainingPlanId;
    }

    public void setTrainingPlanId(String trainingPlanId) {
        this.trainingPlanId = trainingPlanId;
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
        if (!(o instanceof TrainingPlanHorseId that)) return false;
        return Objects.equals(trainingPlanId, that.trainingPlanId) && Objects.equals(horseId, that.horseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trainingPlanId, horseId);
    }
}
