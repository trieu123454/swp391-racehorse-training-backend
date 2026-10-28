package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.StableIncident;

public interface StableIncidentRepository extends JpaRepository<StableIncident, String> {

    List<StableIncident> findByHorseIdOrderByCreatedAtDesc(String horseId);

    List<StableIncident> findByGroomIdOrderByCreatedAtDesc(Long groomId);
}
