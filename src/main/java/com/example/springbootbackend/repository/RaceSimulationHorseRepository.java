package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.RaceSimulationHorse;
import com.example.springbootbackend.entity.RaceSimulationHorseId;

public interface RaceSimulationHorseRepository extends JpaRepository<RaceSimulationHorse, RaceSimulationHorseId> {

    List<RaceSimulationHorse> findByIdSimulationIdOrderByLaneAsc(String simulationId);

    Optional<RaceSimulationHorse> findFirstByIdSimulationIdOrderByLaneAsc(String simulationId);
}
