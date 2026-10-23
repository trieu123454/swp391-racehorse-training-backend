package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.springbootbackend.entity.RaceSimulation;

public interface RaceSimulationRepository extends JpaRepository<RaceSimulation, String> {

    List<RaceSimulation> findByTrainingScheduleIdOrderByCreatedAtDesc(String trainingScheduleId);

    List<RaceSimulation> findByStatusOrderByCreatedAtAsc(String status);

    @Query("""
            SELECT rs.id FROM RaceSimulation rs
            WHERE rs.modelVersion >= 2
              AND (rs.status = 'Running'
                   OR (rs.status = 'Interrupted'
                       AND NOT EXISTS (SELECT 1 FROM RaceSimulationMetric m WHERE m.simulationId = rs.id)))
            """)
    List<String> findIdsRequiringMaintenance();
}
