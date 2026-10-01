package com.example.springbootbackend.headtrainer;

import com.example.springbootbackend.veterinarian.support.*;
import com.example.springbootbackend.veterinarian.horse.HorseTrainingGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs a race and records its results across the training and competition history. */
@Service
@Transactional(readOnly = true)
public class RaceSimulationService {
    private final JdbcTemplate db;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final HorseTrainingGuard trainingGuard;
    private final int injuryAlertHeartRate;
    private final BigDecimal injuryAlertSpeed;

    public RaceSimulationService(JdbcTemplate db, VetAccess access, VetAudit audit, VetTime time,
            HorseTrainingGuard trainingGuard,
            @Value("${app.head-trainer.injury-alert-heart-rate:220}") int injuryAlertHeartRate,
            @Value("${app.head-trainer.injury-alert-speed-kmh:60}") BigDecimal injuryAlertSpeed) {
        this.db = db; this.access = access; this.audit = audit; this.time = time;
        this.trainingGuard = trainingGuard;
        this.injuryAlertHeartRate = injuryAlertHeartRate;
        this.injuryAlertSpeed = injuryAlertSpeed;
    }

    @Transactional
    public Map<String, Object> create(String email, String ip, JsonNode body) {
        long actor = access.requireRole(email, "HEAD_TRAINER");
        Map<String, Object> fields = VetInput.parse(body,
                "distance_meters:int", "duration_seconds:int", "training_schedule_id:uuid");
        VetInput.required(fields, "distance_meters", "duration_seconds");
        int distance = ((Number) fields.get("distance_meters")).intValue();
        int duration = ((Number) fields.get("duration_seconds")).intValue();
        if (distance < 400 || distance > 3200) throw VetException.invalid("distance_meters", "Khoang hop le: 400 den 3200");
        if (duration < 60 || duration > 300) throw VetException.invalid("duration_seconds", "Khoang hop le: 60 den 300");
        List<String> horseIds = horseIds(body == null ? null : body.get("horse_ids"));
        for (String horseId : horseIds.stream().sorted().toList()) trainingGuard.assertHorseNotLocked(horseId);
        String scheduleId = fields.get("training_schedule_id") == null ? null : fields.get("training_schedule_id").toString();
        if (scheduleId != null) {
            var scheduleRows = db.query("SELECT ts.horse_id,ts.status,ts.session_type,ce.event_date,ce.start_time,ce.end_time"
                    + " FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id"
                    + " WHERE ts.id=? FOR UPDATE OF ts", VetRows.MAPPER, scheduleId);
            if (scheduleRows.isEmpty()) throw new VetException(404, "SCHEDULE_NOT_FOUND", "Khong tim thay buoi tap");
            var schedule = scheduleRows.getFirst();
            if (!horseIds.contains(schedule.get("horse_id").toString()))
                throw VetException.invalid("training_schedule_id", "Buoi tap phai thuoc mot ngua tham gia cuoc dua");
            if (!"Scheduled".equals(schedule.get("status")) || "Rest".equals(schedule.get("session_type")))
                throw new VetException(409, "SCHEDULE_NOT_STARTABLE", "Chi lien ket buoi tap dang duoc len lich");
            LocalDate date = (LocalDate) schedule.get("event_date");
            LocalTime start = (LocalTime) schedule.get("start_time"), end = (LocalTime) schedule.get("end_time");
            LocalDateTime now = LocalDateTime.ofInstant(time.now(), VetTime.BUSINESS_ZONE);
            if (start == null || end == null || !date.equals(now.toLocalDate())
                    || now.isBefore(LocalDateTime.of(date, start)) || !now.isBefore(LocalDateTime.of(date, end)))
                throw new VetException(409, "SESSION_OUTSIDE_TIME_WINDOW", "Chi chay cuoc dua trong khung gio cua buoi tap da dat");
        }

        var horses = db.query("SELECT id,horse_name,image_url,breed FROM horses WHERE deleted_at IS NULL AND id IN ("
                        + String.join(",", Collections.nCopies(horseIds.size(), "?")) + ")",
                VetRows.MAPPER, horseIds.toArray());
        if (horses.size() != horseIds.size()) throw new VetException(404, "HORSE_NOT_FOUND", "Khong tim thay ngua");
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (var horse : horses) byId.put(horse.get("id").toString(), horse);

        String simulationId = UUID.randomUUID().toString();
        db.update("INSERT INTO race_simulations(id,distance_meters,duration_seconds,training_schedule_id,status,created_by,created_at)"
                        + " VALUES (?,?,?,?,'Running',?,?)",
                simulationId, distance, duration, scheduleId, actor, time.utcNow());
        if (scheduleId != null) {
            db.update("UPDATE training_schedules SET status='InProgress' WHERE id=?", scheduleId);
            db.update("UPDATE calendar_events SET status='InProgress' WHERE source_table='training_schedules' AND source_id=?", scheduleId);
        }
        List<Map<String, Object>> responseHorses = new ArrayList<>();
        for (int index = 0; index < horseIds.size(); index++) {
            String horseId = horseIds.get(index);
            Map<String, Object> horse = byId.get(horseId);
            int seed = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            double stamina = averageRecentStamina(horseId);
            double maxSpeed = clamp(45 + stamina * 2 + ThreadLocalRandom.current().nextDouble(-3, 3), 35, 65);
            maxSpeed = BigDecimal.valueOf(maxSpeed).setScale(1, RoundingMode.HALF_UP).doubleValue();
            int restingHeartRate = 34;
            int restingSystolic = 110;
            int restingDiastolic = 70;
            int lane = index + 1;
            db.update("INSERT INTO race_simulation_horses(simulation_id,horse_id,lane,seed,base_max_speed_kmh,"
                            + "base_resting_heart_rate,base_bp_systolic,base_bp_diastolic,baseline_stamina_score)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    simulationId, horseId, lane, seed, maxSpeed, restingHeartRate, restingSystolic, restingDiastolic, stamina);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("horse_id", horseId);
            item.put("horse_name", horse.get("horse_name"));
            item.put("image_url", horse.get("image_url"));
            item.put("lane", lane);
            item.put("seed", seed);
            item.put("base_max_speed_kmh", maxSpeed);
            item.put("base_resting_heart_rate", restingHeartRate);
            item.put("base_resting_bp", Map.of("systolic", restingSystolic, "diastolic", restingDiastolic));
            item.put("variance_profile", "normal");
            responseHorses.add(item);
        }
        audit.record(actor, ip, "CREATE_RACE_SIMULATION:" + simulationId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("simulation_id", simulationId);
        response.put("distance_meters", distance);
        response.put("duration_seconds", duration);
        response.put("training_schedule_id", scheduleId);
        response.put("injury_alert_heart_rate", injuryAlertHeartRate);
        response.put("injury_alert_speed_kmh", injuryAlertSpeed);
        response.put("status", "Running");
        response.put("horses", responseHorses);
        return response;
    }

    @Transactional
    public Map<String, Object> finish(String email, String ip, UUID simulationId, JsonNode body) {
        long actor = access.requireRole(email, "HEAD_TRAINER");
        String id = simulationId.toString();
        var simulationRows = db.query("SELECT * FROM race_simulations WHERE id=? FOR UPDATE", VetRows.MAPPER, id);
        if (simulationRows.isEmpty()) throw new VetException(404, "RACE_NOT_FOUND", "Khong tim thay cuoc dua");
        var simulation = simulationRows.getFirst();
        if ("Completed".equals(simulation.get("status")) && simulation.get("race_id") != null)
            return completedResponse(id, simulation.get("race_id").toString());
        if (!"Running".equals(simulation.get("status")))
            throw new VetException(409, "SIMULATION_NOT_RUNNING", "Cuoc dua da ket thuc hoac bi huy");
        int distance = ((Number) simulation.get("distance_meters")).intValue();
        Map<String, Object> finishFields = VetInput.parse(body, "copy_to_metrics:bool");
        List<Map<String, Object>> participants = db.query("SELECT sh.*,h.horse_name FROM race_simulation_horses sh"
                + " JOIN horses h ON h.id=sh.horse_id WHERE sh.simulation_id=? ORDER BY sh.lane", VetRows.MAPPER, id);
        Map<String, Map<String, Object>> laneByHorse = new HashMap<>();
        for (var horse : participants) laneByHorse.put(horse.get("horse_id").toString(), horse);
        List<Map<String, Object>> results = submittedResults(body == null ? null : body.get("results"), participants);

        results.sort(Comparator.comparing((Map<String, Object> row) -> (BigDecimal) row.get("finish_time_seconds"))
                .thenComparing(row -> ((Number) row.get("lane")).intValue()));
        boolean copyToMetrics = Boolean.TRUE.equals(finishFields.get("copy_to_metrics"));
        LocalDateTime resultTime = time.utcNow();
        String linkedScheduleId = simulation.get("training_schedule_id") == null
                ? null : simulation.get("training_schedule_id").toString();
        String linkedScheduleHorse = linkedScheduleId == null ? null : db.query(
                "SELECT horse_id FROM training_schedules WHERE id=?", (rs, row) -> rs.getString(1), linkedScheduleId)
                .stream().findFirst().orElse(null);
        BigDecimal previousTime = null;
        int previousRank = 0;
        for (int index = 0; index < results.size(); index++) {
            var result = results.get(index);
            BigDecimal finishTime = (BigDecimal) result.get("finish_time_seconds");
            int rank = previousTime != null && previousTime.compareTo(finishTime) == 0 ? previousRank : index + 1;
            result.put("rank", rank);
            previousRank = rank;
            previousTime = finishTime;
            String metricId = UUID.randomUUID().toString();
            result.put("metric_id", metricId);
            db.update("INSERT INTO race_simulation_metrics(id,simulation_id,horse_id,lane,finish_time_seconds,"
                            + "avg_speed_kmh,max_heart_rate,max_bp_systolic,max_bp_diastolic,rank,created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    metricId, id, result.get("horse_id"), result.get("lane"), finishTime,
                    result.get("avg_speed_kmh"), result.get("max_heart_rate"), result.get("max_bp_systolic"),
                    result.get("max_bp_diastolic"), rank, resultTime);
        }
        for (var result : results) {
            int heartRate = ((Number) result.get("max_heart_rate")).intValue();
            boolean alert = heartRate > injuryAlertHeartRate
                    || ((BigDecimal) result.get("avg_speed_kmh")).compareTo(injuryAlertSpeed) > 0;
            result.put("has_injury_alert", alert);
            db.update("UPDATE race_simulation_metrics SET has_injury_alert=? WHERE simulation_id=? AND horse_id=?",
                    alert, id, result.get("horse_id"));
            if (copyToMetrics) {
                var participant = laneByHorse.get(result.get("horse_id").toString());
                String metricScheduleId = Objects.equals(linkedScheduleHorse, result.get("horse_id"))
                        ? linkedScheduleId : null;
                db.update("INSERT INTO training_metrics_logs(id,horse_id,training_schedule_id,recorded_by,body_weight_kg,"
                                + "max_heart_rate,avg_speed_kmh,stamina_score,has_injury_alert,trainer_review,bp_systolic,"
                                + "bp_diastolic,is_simulated,recorded_at) VALUES (?,?,?,?,NULL,?,?,?,?,?,?,?,TRUE,?)",
                        result.get("metric_id"),
                        result.get("horse_id"), metricScheduleId, actor, result.get("max_heart_rate"),
                        result.get("avg_speed_kmh"), participant.get("baseline_stamina_score"), alert,
                        "Race simulation result", result.get("max_bp_systolic"), result.get("max_bp_diastolic"), resultTime);
            }
        }
        db.update("UPDATE race_simulations SET status='Completed' WHERE id=?", id);
        if (simulation.get("training_schedule_id") != null) {
            String scheduleId = simulation.get("training_schedule_id").toString();
            db.update("UPDATE training_schedules SET status='Completed' WHERE id=? AND status='InProgress'", scheduleId);
            db.update("UPDATE calendar_events SET status='Completed' WHERE source_table='training_schedules' AND source_id=? AND status='InProgress'", scheduleId);
        }
        LocalDate raceDate = time.today();
        LocalTime raceTime = LocalDateTime.ofInstant(time.now(), VetTime.BUSINESS_ZONE)
                .toLocalTime().withSecond(0).withNano(0);
        String raceId = UUID.randomUUID().toString();
        String raceName = "Cuộc đua " + raceDate + " · " + id.substring(0, 8);
        db.update("INSERT INTO races(id,race_name,distance_meters,race_date,description,is_simulated) VALUES (?,?,?,?,?,TRUE)",
                raceId, raceName, distance, raceDate, "Kết quả cuộc đua được hệ thống ghi nhận.");
        db.update("UPDATE race_simulations SET race_id=? WHERE id=?", raceId, id);
        for (var result : results) {
            String entryId = UUID.randomUUID().toString();
            String eventId = UUID.randomUUID().toString();
            db.update("INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,status,source_table,source_id,created_by,created_at)"
                            + " VALUES (?,?, 'Race',?,?,?,'Completed','horse_race_entries',?,?,?)",
                    eventId, result.get("horse_id"), raceName, raceDate, raceTime, entryId, actor, time.utcNow());
            db.update("INSERT INTO horse_race_entries(id,calendar_event_id,horse_id,race_id,registered_by,result_position,prize_amount,status,created_at)"
                            + " VALUES (?,?,?,?,?,?,0,'Completed',?)",
                    entryId, eventId, result.get("horse_id"), raceId, actor, result.get("rank"), time.utcNow());
        }
        audit.record(actor, ip, "FINISH_RACE_SIMULATION:" + id);
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (var result : results) {
            var participant = laneByHorse.get(result.get("horse_id").toString());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", result.get("rank"));
            item.put("horse_id", result.get("horse_id"));
            item.put("horse_name", participant.get("horse_name"));
            item.put("finish_time_seconds", result.get("finish_time_seconds"));
            ranking.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("simulation_id", id);
        response.put("race_id", raceId);
        response.put("race_name", raceName);
        response.put("status", "Completed");
        response.put("ranking", ranking);
        return response;
    }

    private Map<String, Object> completedResponse(String simulationId, String raceId) {
        var races = db.query("SELECT race_name FROM races WHERE id=?", VetRows.MAPPER, raceId);
        var ranking = db.query("SELECT m.rank,m.horse_id,h.horse_name,m.finish_time_seconds"
                        + " FROM race_simulation_metrics m JOIN horses h ON h.id=m.horse_id"
                        + " WHERE m.simulation_id=? ORDER BY m.rank,m.lane", VetRows.MAPPER, simulationId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("simulation_id", simulationId);
        response.put("race_id", raceId);
        response.put("race_name", races.isEmpty() ? "Cuộc đua" : races.getFirst().get("race_name"));
        response.put("status", "Completed");
        response.put("ranking", ranking);
        return response;
    }

    private List<Map<String, Object>> submittedResults(JsonNode submitted, List<Map<String, Object>> participants) {
        if (submitted == null || !submitted.isArray() || submitted.size() != participants.size())
            throw VetException.invalid("results", "Phai gui dung mot ket qua cho moi ngua tham gia");
        Map<String, Map<String, Object>> participantById = new HashMap<>();
        for (var participant : participants) participantById.put(participant.get("horse_id").toString(), participant);
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode input : submitted) {
            Map<String, Object> fields = VetInput.parse(input, "horse_id:uuid", "finish_time_seconds:number",
                    "avg_speed_kmh:number", "max_heart_rate:int", "max_bp_systolic:int", "max_bp_diastolic:int");
            VetInput.required(fields, "horse_id", "finish_time_seconds", "avg_speed_kmh", "max_heart_rate",
                    "max_bp_systolic", "max_bp_diastolic");
            String horseId = fields.get("horse_id").toString();
            Map<String, Object> participant = participantById.get(horseId);
            if (participant == null || !seen.add(horseId))
                throw VetException.invalid("results", "Ket qua phai co dung mot dong cho moi ngua tham gia");
            VetInput.number(fields, "finish_time_seconds", "0.01", "9999.99", 2);
            VetInput.number(fields, "avg_speed_kmh", "0.01", "90", 2);
            int heartRate = ((Number) fields.get("max_heart_rate")).intValue();
            int systolic = ((Number) fields.get("max_bp_systolic")).intValue();
            int diastolic = ((Number) fields.get("max_bp_diastolic")).intValue();
            if (heartRate < 20 || heartRate > 260) throw VetException.invalid("max_heart_rate", "Khoang hop le: 20 den 260");
            if (systolic < 40 || systolic > 300) throw VetException.invalid("max_bp_systolic", "Khoang hop le: 40 den 300");
            if (diastolic < 20 || diastolic > 200) throw VetException.invalid("max_bp_diastolic", "Khoang hop le: 20 den 200");
            Map<String, Object> result = new LinkedHashMap<>(fields);
            result.put("lane", participant.get("lane"));
            result.put("rank", null);
            results.add(result);
        }
        return results;
    }

    public Map<String, Object> detail(String email, UUID simulationId) {
        access.requireRole(email, "HEAD_TRAINER");
        String id = simulationId.toString();
        var rows = db.query("SELECT * FROM race_simulations WHERE id=?", VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "RACE_NOT_FOUND", "Khong tim thay cuoc dua");
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        result.put("horses", db.query("SELECT sh.*,h.horse_name,h.image_url FROM race_simulation_horses sh"
                + " JOIN horses h ON h.id=sh.horse_id WHERE sh.simulation_id=? ORDER BY sh.lane", VetRows.MAPPER, id));
        result.put("results", db.query("SELECT m.*,h.horse_name,sh.baseline_stamina_score AS stamina_score FROM race_simulation_metrics m"
                + " JOIN horses h ON h.id=m.horse_id JOIN race_simulation_horses sh ON sh.simulation_id=m.simulation_id AND sh.horse_id=m.horse_id"
                + " WHERE m.simulation_id=? ORDER BY m.rank,m.lane", VetRows.MAPPER, id));
        return result;
    }

    private Map<String, Object> calculateResult(Map<String, Object> horse, int distanceMeters) {
        Map<String, Object> result = new LinkedHashMap<>();
        String horseId = horse.get("horse_id").toString();
        int seed = ((Number) horse.get("seed")).intValue();
        double baseSpeed = ((Number) horse.get("base_max_speed_kmh")).doubleValue();
        int restingHeartRate = ((Number) horse.get("base_resting_heart_rate")).intValue();
        int restingSystolic = ((Number) horse.get("base_bp_systolic")).intValue();
        int restingDiastolic = ((Number) horse.get("base_bp_diastolic")).intValue();
        double speedSum = 0;
        int maxHeartRate = 0, maxSystolic = 0, maxDiastolic = 0;
        for (int index = 0; index <= 100; index++) {
            double progress = index / 100.0;
            double speed = Math.max(0, baseSpeed * (speedFactor(progress)
                    + (seededNoise(seed, progress, 0) - 0.5) * 0.03));
            double ratio = baseSpeed <= 0 ? 0 : speed / baseSpeed;
            int heartRate = (int) Math.round(Math.max(restingHeartRate, Math.min(230,
                    restingHeartRate + (220 - restingHeartRate) * ratio * 0.7
                            + (seededNoise(seed, progress, 0x4f1bbcdc) - 0.5) * 7)));
            int systolic = (int) Math.round(Math.max(40, Math.min(300,
                    restingSystolic + ratio * 40 + (seededNoise(seed, progress, 0x7a143589) - 0.5) * 8)));
            int diastolic = (int) Math.round(Math.max(60, Math.min(90,
                    restingDiastolic + 3 * Math.sin(progress * Math.PI * 2)
                            + (seededNoise(seed, progress, 0x1b873593) - 0.5) * 6)));
            speedSum += speed;
            maxHeartRate = Math.max(maxHeartRate, heartRate);
            maxSystolic = Math.max(maxSystolic, systolic);
            maxDiastolic = Math.max(maxDiastolic, diastolic);
        }
        double averageSpeed = speedSum / 101;
        result.put("horse_id", horseId);
        result.put("lane", horse.get("lane"));
        result.put("finish_time_seconds", BigDecimal.valueOf(distanceMeters / (averageSpeed / 3.6)).setScale(2, RoundingMode.HALF_UP));
        result.put("avg_speed_kmh", BigDecimal.valueOf(averageSpeed).setScale(2, RoundingMode.HALF_UP));
        result.put("max_heart_rate", maxHeartRate);
        result.put("max_bp_systolic", maxSystolic);
        result.put("max_bp_diastolic", maxDiastolic);
        result.put("stamina_score", horse.get("baseline_stamina_score"));
        return result;
    }

    private double speedFactor(double progress) {
        double p = clamp(progress, 0, 1);
        if (p < 0.15) {
            double x = p / 0.15;
            double eased = x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
            return 0.93 * eased;
        }
        if (p < 0.8) return 0.94 + 0.035 * Math.sin(((p - 0.15) / 0.65) * Math.PI);
        return 0.975 - 0.105 * ((p - 0.8) / 0.2);
    }

    private double seededNoise(int seed, double progress, int salt) {
        int sample = (int) Math.floor(clamp(progress, 0, 1) * 1000);
        int value = seed ^ salt ^ (sample * 0x45d9f3b);
        value ^= value >>> 16;
        value *= 0x45d9f3b;
        value ^= value >>> 16;
        return (value & 0x7fffffff) / (double) 0x7fffffff;
    }

    private List<String> horseIds(JsonNode node) {
        if (node == null || !node.isArray()) throw VetException.invalid("horse_ids", "Phai la mang UUID");
        if (node.size() < 2 || node.size() > 5) throw VetException.invalid("horse_ids", "So ngua phai tu 2 den 5");
        List<String> ids = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) throw VetException.invalid("horse_ids", "Moi ID phai la UUID hop le");
            String id;
            try { id = UUID.fromString(value.textValue()).toString(); }
            catch (IllegalArgumentException error) { throw VetException.invalid("horse_ids", "Moi ID phai la UUID hop le"); }
            if (ids.contains(id)) throw VetException.invalid("horse_ids", "Khong duoc lap ID ngua");
            ids.add(id);
        }
        return ids;
    }

    private double averageRecentStamina(String horseId) {
        var values = db.queryForList("SELECT stamina_score FROM shared_training_metrics WHERE horse_id=? AND is_simulated=FALSE"
                + " AND stamina_score IS NOT NULL ORDER BY recorded_at DESC,id DESC LIMIT 3", horseId);
        if (values.isEmpty()) return 5;
        double sum = 0;
        for (var row : values) sum += ((Number) row.get("stamina_score")).doubleValue();
        return sum / values.size();
    }

    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

}
