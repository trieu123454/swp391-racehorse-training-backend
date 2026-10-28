package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.TrainingVideo;

public interface TrainingVideoRepository extends JpaRepository<TrainingVideo, String> {

    List<TrainingVideo> findByHorseIdOrderByUploadedAtDesc(String horseId);
}
