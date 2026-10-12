package com.example.springbootbackend.headtrainer;

import com.example.springbootbackend.veterinarian.support.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!test")
@EnableScheduling
public class TrainingSessionMaintenance {
    private static final Logger log=LoggerFactory.getLogger(TrainingSessionMaintenance.class);
    private final JdbcTemplate db;
    private final RaceSimulationService simulations;
    private final VetTime time;
    public TrainingSessionMaintenance(JdbcTemplate db,RaceSimulationService simulations,VetTime time) {
        this.db=db; this.simulations=simulations; this.time=time;
    }
    @Scheduled(fixedDelayString="${app.head-trainer.session-maintenance-ms:15000}",initialDelay=15000)
    public void tick() {
        var running=db.query("SELECT rs.id FROM race_simulations rs WHERE rs.model_version>=2 AND (rs.status='Running'"
                + " OR (rs.status='Interrupted' AND NOT EXISTS (SELECT 1 FROM race_simulation_metrics m WHERE m.simulation_id=rs.id)))",VetRows.MAPPER);
        for(var row:running) {
            try { simulations.advance(UUID.fromString(row.get("id").toString())); }
            catch(RuntimeException failure) { log.warn("Cannot update training simulation {}: {}",row.get("id"),failure.getMessage()); }
        }
        // The service updates calendar and schedule states within the same transaction.
        var expired=db.query("SELECT ts.id FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id"
                + " WHERE ts.status='Scheduled' AND ts.session_type<>'Rest'"
                + " AND ce.event_date<=?",VetRows.MAPPER,time.today());
        for(var row:expired) {
            try { simulations.expireSchedule(UUID.fromString(row.get("id").toString())); }
            catch(VetException failure) { if(failure.getMessage()!=null) log.debug("Session {} is not missable: {}",row.get("id"),failure.getMessage()); }
        }
    }
}
