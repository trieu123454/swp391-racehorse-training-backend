package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.RoleChangeRequest;

public interface RoleChangeRequestRepository extends JpaRepository<RoleChangeRequest, String> {

    List<RoleChangeRequest> findByStatusOrderByCreatedAtDescIdDesc(String status);

    List<RoleChangeRequest> findAllByOrderByCreatedAtDescIdDesc();
}
