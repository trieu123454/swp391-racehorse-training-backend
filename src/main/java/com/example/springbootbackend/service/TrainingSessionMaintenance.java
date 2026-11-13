package com.example.springbootbackend.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.springbootbackend.exception.VetException;
import com.example.springbootbackend.repository.RaceSimulationRepository;
import com.example.springbootbackend.repository.TrainingScheduleRepository;

@Component
@Profile("!test")
@EnableScheduling
public class TrainingSessionMaintenance {
    private static final Logger log = LoggerFactory.getLogger(TrainingSessionMaintenance.class);
    private final RaceSimulationRepository raceSimulations;
    private final TrainingScheduleRepository trainingSchedules;
    private final RaceSimulationService simulations;
    private final VetTime time;

    public TrainingSessionMaintenance(
            RaceSimulationRepository raceSimulations,
            TrainingScheduleRepository trainingSchedules,
            RaceSimulationService simulations,
            VetTime time) {
        this.raceSimulations = raceSimulations;
        this.trainingSchedules = trainingSchedules;
        this.simulations = simulations;
        this.time = time;
    }

    @Scheduled(fixedDelayString = "${app.head-trainer.session-maintenance-ms:15000}", initialDelay = 15000)
    public void tick() {
        var running = raceSimulations.findIdsRequiringMaintenance();
        for (String id : running) {
            try {
                simulations.advance(UUID.fromString(id.stripTrailing()));
            } catch (RuntimeException failure) {
                log.warn("Cannot update training simulation {}: {}", id, failure.getMessage());
            }
        }
        // The service updates calendar and schedule states within the same transaction.
        LocalTime nowTime = LocalDateTime.ofInstant(time.now(), VetTime.BUSINESS_ZONE).toLocalTime();
        var expired = trainingSchedules.findScheduledNonRestIdsUpTo(time.today(), nowTime);
        for (String id : expired) {
            try {
                simulations.expireSchedule(UUID.fromString(id.stripTrailing()));
            } catch (VetException failure) {
                if (failure.getMessage() != null) {
                    log.debug("Session {} is not missable: {}", id, failure.getMessage());
                }
            }
        }
    }
}
