package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.HealthExam;

public interface HealthExamRepository extends JpaRepository<HealthExam, String> {

    List<HealthExam> findByHorseIdOrderByExamDateDesc(String horseId);
}
