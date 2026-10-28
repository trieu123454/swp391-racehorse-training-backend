package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.CalendarEvent;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, String> {

    Optional<CalendarEvent> findBySourceTableAndSourceId(String sourceTable, String sourceId);

    List<CalendarEvent> findByHorseIdOrderByEventDateAscStartTimeAsc(String horseId);

    void deleteBySourceTableAndSourceId(String sourceTable, String sourceId);
}
