package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.springbootbackend.entity.PasswordResetCode;

@Repository
public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, String> {

    Optional<PasswordResetCode> findTopByEmailIgnoreCaseAndCodeAndUsedFalseOrderByCreatedAtDesc(String email, String code);

    List<PasswordResetCode> findByEmailIgnoreCaseAndUsedFalse(String email);
}

