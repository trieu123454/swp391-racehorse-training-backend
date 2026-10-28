package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.Race;

public interface RaceRepository extends JpaRepository<Race, String> {

    List<Race> findAllByOrderByRaceDateDesc();
}
