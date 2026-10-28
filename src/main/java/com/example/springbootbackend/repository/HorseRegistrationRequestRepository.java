package com.example.springbootbackend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.example.springbootbackend.entity.HorseRegistrationRequest;

public interface HorseRegistrationRequestRepository extends JpaRepository<HorseRegistrationRequest, String> {

    List<HorseRegistrationRequest> findByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId);

    Optional<HorseRegistrationRequest> findByIdAndOwnerId(String id, Long ownerId);

    List<HorseRegistrationRequest> findByStatusOrderByCreatedAtDescIdDesc(String status);

    List<HorseRegistrationRequest> findByStatusOrderByCreatedAtAscIdAsc(String status);

    List<HorseRegistrationRequest> findByStatusAndApprovedHorseIdOrderByCreatedAtAscIdAsc(
            String status, String approvedHorseId);

    boolean existsByRequestTypeAndStatusInAndMicrochipNumberIgnoreCase(
            String requestType, List<String> statuses, String microchipNumber);

    boolean existsByRequestTypeAndStatusInAndPassportNumberIgnoreCase(
            String requestType, List<String> statuses, String passportNumber);

    @Query("select case when count(r) > 0 then true else false end "
            + "from HorseRegistrationRequest r where r.requestType = :requestType "
            + "and r.status in :statuses and lower(r.microchipNumber) = lower(:value) "
            + "and (:excludeId is null or r.id <> :excludeId)")
    boolean existsOtherActiveMicrochip(@Param("requestType") String requestType,
            @Param("statuses") List<String> statuses, @Param("value") String value,
            @Param("excludeId") String excludeId);

    @Query("select case when count(r) > 0 then true else false end "
            + "from HorseRegistrationRequest r where r.requestType = :requestType "
            + "and r.status in :statuses and lower(r.passportNumber) = lower(:value) "
            + "and (:excludeId is null or r.id <> :excludeId)")
    boolean existsOtherActivePassport(@Param("requestType") String requestType,
            @Param("statuses") List<String> statuses, @Param("value") String value,
            @Param("excludeId") String excludeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from HorseRegistrationRequest r where r.id = :id")
    Optional<HorseRegistrationRequest> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from HorseRegistrationRequest r where r.id = :id and r.ownerId = :ownerId")
    Optional<HorseRegistrationRequest> findByIdAndOwnerIdForUpdate(
            @Param("id") String id, @Param("ownerId") Long ownerId);

    List<HorseRegistrationRequest> findByApprovedHorseIdInAndStatus(
            List<String> horseIds, String status);

    boolean existsByApprovedHorseIdAndStatus(String approvedHorseId, String status);

    List<HorseRegistrationRequest> findAllByOrderByCreatedAtDescIdDesc();
}
