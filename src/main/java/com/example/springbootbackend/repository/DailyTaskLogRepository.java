package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.DailyTaskLog;

public interface DailyTaskLogRepository extends JpaRepository<DailyTaskLog, String> {

    List<DailyTaskLog> findByHorseId(String horseId);
}
