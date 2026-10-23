package com.example.springbootbackend.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "training_plan_horses")
public class TrainingPlanHorse {

    @EmbeddedId
    private TrainingPlanHorseId id;

    public TrainingPlanHorse() {
    }

    public TrainingPlanHorse(String trainingPlanId, String horseId) {
        this.id = new TrainingPlanHorseId(trainingPlanId, horseId);
    }

    public TrainingPlanHorseId getId() {
        return id;
    }

    public void setId(TrainingPlanHorseId id) {
        this.id = id;
    }
}
