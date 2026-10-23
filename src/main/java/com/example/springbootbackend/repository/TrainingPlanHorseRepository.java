package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.TrainingPlanHorse;
import com.example.springbootbackend.entity.TrainingPlanHorseId;

public interface TrainingPlanHorseRepository extends JpaRepository<TrainingPlanHorse, TrainingPlanHorseId> {

    List<TrainingPlanHorse> findByIdTrainingPlanId(String trainingPlanId);

    List<TrainingPlanHorse> findByIdHorseId(String horseId);

    boolean existsByIdTrainingPlanIdAndIdHorseId(String trainingPlanId, String horseId);

    void deleteByIdTrainingPlanId(String trainingPlanId);
}
