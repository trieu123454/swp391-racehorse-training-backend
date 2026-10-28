package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.springbootbackend.entity.Horse;

import jakarta.persistence.LockModeType;

public interface HorseRepository extends JpaRepository<Horse, String> {

    Optional<Horse> findByIdAndDeletedAtIsNull(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Horse h where h.id = :id and h.deletedAt is null")
    Optional<Horse> findActiveByIdForUpdate(@Param("id") String id);

    List<Horse> findByDeletedAtIsNullOrderByHorseNameAsc();

    List<Horse> findByOwnerIdAndDeletedAtIsNullOrderByHorseNameAsc(Long ownerId);

    long countByStableBoxIdAndDeletedAtIsNull(String stableBoxId);

    long countByStableBoxIdAndDeletedAtIsNullAndIdNot(String stableBoxId, String id);

    boolean existsByIdAndOwnerIdAndDeletedAtIsNull(String id, Long ownerId);

    boolean existsByMicrochipNumberIgnoreCase(String microchipNumber);

    boolean existsByPassportNumberIgnoreCase(String passportNumber);

    boolean existsByMicrochipNumberIgnoreCaseAndIdNot(String microchipNumber, String id);

    boolean existsByPassportNumberIgnoreCaseAndIdNot(String passportNumber, String id);
}
