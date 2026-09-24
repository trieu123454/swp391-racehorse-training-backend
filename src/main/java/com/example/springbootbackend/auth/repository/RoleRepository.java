package com.example.springbootbackend.auth.repository;

import java.util.Optional;

import com.example.springbootbackend.auth.entity.Role;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Integer> {

    Optional<Role> findByName(String name);

    boolean existsByName(String name);
}
