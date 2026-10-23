package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.AppUser;
import com.example.springbootbackend.entity.UserStatus;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Page<AppUser> findByStatus(UserStatus status, Pageable pageable);

    Page<AppUser> findByStatusAndDeletedAtIsNull(UserStatus status, Pageable pageable);

    List<AppUser> findByDeletedAtIsNullOrderByIdAsc();
}
