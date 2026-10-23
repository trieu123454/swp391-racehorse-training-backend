package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.StableBox;

public interface StableBoxRepository extends JpaRepository<StableBox, String> {

    Optional<StableBox> findByBoxCodeIgnoreCase(String boxCode);

    boolean existsByBoxCodeIgnoreCase(String boxCode);

    boolean existsByBoxCodeIgnoreCaseAndIdNot(String boxCode, String id);

    List<StableBox> findAllByOrderByBoxCodeAsc();
}
