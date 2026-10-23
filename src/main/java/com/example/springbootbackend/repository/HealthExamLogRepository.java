package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.HealthExamLog;

public interface HealthExamLogRepository extends JpaRepository<HealthExamLog, String> {

    List<HealthExamLog> findByHealthExamIdOrderByEditedAtDesc(String healthExamId);
}
