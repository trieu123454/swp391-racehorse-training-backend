package com.example.springbootbackend.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.example.springbootbackend.entity.HorseIdentityLock;
import jakarta.persistence.LockModeType;

public interface HorseIdentityLockRepository extends JpaRepository<HorseIdentityLock, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from HorseIdentityLock l where l.id = :id")
    Optional<HorseIdentityLock> findByIdForUpdate(@Param("id") Integer id);
}
