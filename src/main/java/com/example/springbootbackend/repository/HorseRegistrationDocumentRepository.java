package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.HorseRegistrationDocument;

public interface HorseRegistrationDocumentRepository extends JpaRepository<HorseRegistrationDocument, String> {

    List<HorseRegistrationDocument> findByRequestIdOrderByUploadedAtAscIdAsc(String requestId);

    Optional<HorseRegistrationDocument> findByIdAndRequestId(String id, String requestId);
}
