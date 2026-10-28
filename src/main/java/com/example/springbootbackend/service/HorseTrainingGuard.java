package com.example.springbootbackend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.springbootbackend.entity.Horse;
import com.example.springbootbackend.exception.VetException;
import com.example.springbootbackend.repository.HorseRepository;

/** Shared horse existence and training-lock guard for every module that books real care or training. */
@Component
public class HorseTrainingGuard {
    private final HorseRepository horseRepository;

    public HorseTrainingGuard(HorseRepository horseRepository) {
        this.horseRepository = horseRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertHorseNotLocked(String horseId) {
        if (isHorseBlocked(horseId)) throw trainingLocked(horseId);
    }

    /** Locks the horse row and reports either an explicit lock or a medically unsafe status. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isHorseBlocked(String horseId) {
        Horse horse = horseRepository.findActiveByIdForUpdate(horseId)
                .orElseThrow(() -> new VetException(404, "HORSE_NOT_FOUND", "Không tìm thấy ngựa"));
        return horse.isTrainingLocked()
                || List.of("Injured", "Quarantine").contains(horse.getCurrentStatus());
    }

    private VetException trainingLocked(String horseId) {
        Horse horse = horseRepository.findByIdAndDeletedAtIsNull(horseId)
                .orElseThrow(() -> new VetException(404, "HORSE_NOT_FOUND", "Không tìm thấy ngựa"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lock_level", horse.getLockLevel());
        details.put("lock_reason", horse.getLockReason());
        details.put("current_status", horse.getCurrentStatus());
        throw new VetException(409, "TRAINING_LOCKED", "Horse training is locked or medically restricted", details);
    }
}
