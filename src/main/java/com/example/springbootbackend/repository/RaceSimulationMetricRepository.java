package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.RaceSimulationMetric;

public interface RaceSimulationMetricRepository extends JpaRepository<RaceSimulationMetric, String> {

    List<RaceSimulationMetric> findBySimulationIdOrderByRankAsc(String simulationId);

    Optional<RaceSimulationMetric> findBySimulationIdAndHorseId(String simulationId, String horseId);

    boolean existsBySimulationId(String simulationId);
}
