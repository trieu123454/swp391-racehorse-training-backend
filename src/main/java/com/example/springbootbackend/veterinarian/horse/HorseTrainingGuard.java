package com.example.springbootbackend.veterinarian.horse;

import com.example.springbootbackend.veterinarian.support.VetAccess;
import com.example.springbootbackend.veterinarian.support.VetException;
import com.example.springbootbackend.veterinarian.support.VetRows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared horse existence and training-lock guard for every module that books real care or training. */
@Component
public class HorseTrainingGuard {
    private final JdbcTemplate db;
    private final VetAccess access;

    public HorseTrainingGuard(JdbcTemplate db, VetAccess access) {
        this.db = db;
        this.access = access;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertHorseNotLocked(String horseId) {
        if (isHorseBlocked(horseId)) throw trainingLocked(horseId);
    }

    /** Locks the horse row and reports either an explicit lock or a medically unsafe status. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isHorseBlocked(String horseId) {
        access.requireHorse(horseId, true);
        var rows = db.query("SELECT is_training_locked,lock_level,lock_reason,current_status FROM horses WHERE id=?", VetRows.MAPPER, horseId);
        if (rows.isEmpty()) throw new VetException(404, "HORSE_NOT_FOUND", "Horse not found");
        var horse = rows.getFirst();
        return Boolean.TRUE.equals(horse.get("is_training_locked"))
                || List.of("Injured", "Quarantine").contains(horse.get("current_status"));
    }

    private VetException trainingLocked(String horseId) {
        var rows = db.query("SELECT is_training_locked,lock_level,lock_reason,current_status FROM horses WHERE id=?", VetRows.MAPPER, horseId);
        if (rows.isEmpty()) throw new VetException(404, "HORSE_NOT_FOUND", "Horse not found");
        var horse = rows.getFirst();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lock_level", horse.get("lock_level"));
        details.put("lock_reason", horse.get("lock_reason"));
        details.put("current_status", horse.get("current_status"));
        throw new VetException(409, "TRAINING_LOCKED", "Horse training is locked or medically restricted", details);
    }
}
