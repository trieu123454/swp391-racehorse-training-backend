package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.HorseOwnershipHistory;

public interface HorseOwnershipHistoryRepository extends JpaRepository<HorseOwnershipHistory, String> {

    List<HorseOwnershipHistory> findByHorseIdOrderByChangedAtDescIdDesc(String horseId);
}
