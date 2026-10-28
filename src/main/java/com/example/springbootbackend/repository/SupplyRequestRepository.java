package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.SupplyRequest;

public interface SupplyRequestRepository extends JpaRepository<SupplyRequest, String> {

    List<SupplyRequest> findByRequestedByOrderByCreatedAtDesc(Long requestedBy);

    boolean existsByRequestedByAndItemIdAndStatus(Long requestedBy, String itemId, String status);

    long countByStatus(String status);
}
