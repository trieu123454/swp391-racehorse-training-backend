package com.example.springbootbackend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.HorseImageUpload;

public interface HorseImageUploadRepository extends JpaRepository<HorseImageUpload, String> {

    Optional<HorseImageUpload> findByObjectPathAndUploadedBy(String objectPath, Long uploadedBy);

    boolean existsByObjectPathAndUploadedBy(String objectPath, Long uploadedBy);
}
