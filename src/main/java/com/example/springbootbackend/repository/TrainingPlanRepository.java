package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.TrainingPlan;

public interface TrainingPlanRepository extends JpaRepository<TrainingPlan, String> {

    List<TrainingPlan> findByHorseIdOrderByStartDateDescCreatedAtDesc(String horseId);
}
