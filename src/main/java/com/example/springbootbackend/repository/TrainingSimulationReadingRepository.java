package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.TrainingSimulationReading;
import com.example.springbootbackend.entity.TrainingSimulationReadingId;

public interface TrainingSimulationReadingRepository extends JpaRepository<TrainingSimulationReading, TrainingSimulationReadingId> {

    List<TrainingSimulationReading> findByIdSimulationIdOrderByIdElapsedSecondsAsc(String simulationId);

    List<TrainingSimulationReading> findByIdSimulationIdAndIdElapsedSecondsLessThanEqualOrderByIdElapsedSecondsAsc(
            String simulationId,
            Integer elapsedSeconds
    );
}
