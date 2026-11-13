package com.example.springbootbackend.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.HorseRegistrationDocument;

public interface HorseRegistrationDocumentRepository extends JpaRepository<HorseRegistrationDocument, String> {

    List<HorseRegistrationDocument> findByRequestIdOrderByUploadedAtAscIdAsc(String requestId);

    List<HorseRegistrationDocument> findByRequestIdInOrderByUploadedAtAscIdAsc(Collection<String> requestIds);

    Optional<HorseRegistrationDocument> findByIdAndRequestId(String id, String requestId);
}
