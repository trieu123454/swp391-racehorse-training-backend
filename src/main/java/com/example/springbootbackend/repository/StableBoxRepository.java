package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.example.springbootbackend.entity.StableBox;

public interface StableBoxRepository extends JpaRepository<StableBox, String> {

    Optional<StableBox> findByBoxCodeIgnoreCase(String boxCode);

    boolean existsByBoxCodeIgnoreCase(String boxCode);

    boolean existsByBoxCodeIgnoreCaseAndIdNot(String boxCode, String id);

    List<StableBox> findAllByOrderByBoxCodeAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StableBox b where b.id = :id")
    Optional<StableBox> findByIdForUpdate(@Param("id") String id);
}
