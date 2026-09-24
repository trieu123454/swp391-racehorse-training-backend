package com.example.springbootbackend.auth.repository;

import java.util.Optional;

import com.example.springbootbackend.auth.entity.RefreshToken;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);
}
