package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.TrainingMetricsLog;

public interface TrainingMetricsLogRepository extends JpaRepository<TrainingMetricsLog, String> {

    List<TrainingMetricsLog> findByHorseIdOrderByRecordedAtDesc(String horseId);

    List<TrainingMetricsLog> findByTrainingScheduleId(String trainingScheduleId);
}
