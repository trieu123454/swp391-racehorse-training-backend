package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.springbootbackend.entity.AppUser;
import com.example.springbootbackend.entity.UserStatus;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Page<AppUser> findByStatus(UserStatus status, Pageable pageable);

    Page<AppUser> findByStatusAndDeletedAtIsNull(UserStatus status, Pageable pageable);

    List<AppUser> findByDeletedAtIsNullOrderByIdAsc();

    @Query("select u from AppUser u where u.role.name = :roleName and u.status = :status and u.deletedAt is null")
    List<AppUser> findActiveByRoleName(@Param("roleName") String roleName, @Param("status") UserStatus status);
}
