package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.Prescription;

public interface PrescriptionRepository extends JpaRepository<Prescription, String> {

    List<Prescription> findByHorseIdOrderByCreatedAtDesc(String horseId);

    List<Prescription> findByMedicalRecordIdOrderByCreatedAtAsc(String medicalRecordId);
}
