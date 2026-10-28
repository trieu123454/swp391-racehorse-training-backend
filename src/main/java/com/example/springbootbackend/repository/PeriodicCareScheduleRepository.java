package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.PeriodicCareSchedule;

public interface PeriodicCareScheduleRepository extends JpaRepository<PeriodicCareSchedule, String> {

    List<PeriodicCareSchedule> findByHorseIdOrderByNextDueDateAsc(String horseId);
}
