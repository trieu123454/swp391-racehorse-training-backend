package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.DietRecord;

public interface DietRecordRepository extends JpaRepository<DietRecord, String> {

    List<DietRecord> findByHorseIdOrderByCreatedAtDesc(String horseId);
}
