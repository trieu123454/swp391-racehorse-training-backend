package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.HorseRegistrationRequest;

public interface HorseRegistrationRequestRepository extends JpaRepository<HorseRegistrationRequest, String> {

    List<HorseRegistrationRequest> findByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId);

    Optional<HorseRegistrationRequest> findByIdAndOwnerId(String id, Long ownerId);

    List<HorseRegistrationRequest> findByStatusOrderByCreatedAtDescIdDesc(String status);

    List<HorseRegistrationRequest> findAllByOrderByCreatedAtDescIdDesc();
}
