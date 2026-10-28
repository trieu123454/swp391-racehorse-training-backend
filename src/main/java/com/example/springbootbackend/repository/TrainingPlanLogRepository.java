package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.TrainingPlanLog;

public interface TrainingPlanLogRepository extends JpaRepository<TrainingPlanLog, String> {

    List<TrainingPlanLog> findByTrainingPlanIdOrderByChangedAtDesc(String trainingPlanId);
}
