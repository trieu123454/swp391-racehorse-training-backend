package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.HorseRaceEntry;

public interface HorseRaceEntryRepository extends JpaRepository<HorseRaceEntry, String> {

    List<HorseRaceEntry> findByRaceId(String raceId);

    List<HorseRaceEntry> findByHorseId(String horseId);
}
