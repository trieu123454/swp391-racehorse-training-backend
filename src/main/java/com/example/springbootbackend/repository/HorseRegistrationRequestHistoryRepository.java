package com.example.springbootbackend.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.springbootbackend.entity.HorseRegistrationRequestHistory;

public interface HorseRegistrationRequestHistoryRepository
        extends JpaRepository<HorseRegistrationRequestHistory, String> {
    List<HorseRegistrationRequestHistory> findByRequestIdOrderByCreatedAtAscIdAsc(String requestId);
}
