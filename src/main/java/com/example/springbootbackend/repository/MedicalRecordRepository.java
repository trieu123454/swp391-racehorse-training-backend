package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.MedicalRecord;

public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, String> {

    List<MedicalRecord> findByHorseIdOrderByCreatedAtDesc(String horseId);
}
