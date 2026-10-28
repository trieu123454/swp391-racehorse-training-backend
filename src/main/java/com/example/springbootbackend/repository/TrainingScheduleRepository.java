package com.example.springbootbackend.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.springbootbackend.entity.TrainingSchedule;

public interface TrainingScheduleRepository extends JpaRepository<TrainingSchedule, String> {

    List<TrainingSchedule> findByHorseId(String horseId);

    List<TrainingSchedule> findByTrainingPlanId(String trainingPlanId);

    long countByTrainingPlanId(String trainingPlanId);

    @Query("""
            SELECT ts.id FROM TrainingSchedule ts, CalendarEvent ce
            WHERE ce.id = ts.calendarEventId
              AND ts.status = 'Scheduled'
              AND ts.sessionType <> 'Rest'
              AND ce.eventDate <= :today
            """)
    List<String> findScheduledNonRestIdsUpToDate(@Param("today") LocalDate today);
}
