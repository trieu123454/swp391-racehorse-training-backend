package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.InjuryMarker;

public interface InjuryMarkerRepository extends JpaRepository<InjuryMarker, String> {

    List<InjuryMarker> findByHorseIdOrderByMarkedAtDesc(String horseId);

    List<InjuryMarker> findByMedicalRecordIdOrderByMarkedAtAsc(String medicalRecordId);
}
