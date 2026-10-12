package com.example.springbootbackend.horseowner.repository;

import com.example.springbootbackend.veterinarian.care.CalendarEventSources;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read queries for the Horse Owner portal. Horse ownership is part of every horse query. */
@Repository
public class HorseOwnerRepository {
    private static final String HORSE_SELECT = """
            SELECT h.id,h.horse_name,h.breed,h.birth_year,h.pedigree_father,h.pedigree_mother,
                   h.image_url,h.current_status,h.current_weight_kg,h.height_cm,h.readiness_status,
                   h.is_training_locked,h.lock_level,h.lock_reason,h.created_at,
                   h.stable_box_id,h.owner_id,s.box_code,s.section,u.full_name AS owner_name
            FROM horses h
            LEFT JOIN stable_boxes s ON s.id=h.stable_box_id
            LEFT JOIN users u ON u.user_id=h.owner_id
            """;

    private final JdbcTemplate db;

    public HorseOwnerRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Long findApprovedOwnerId(String email) {
        List<Long> ids = db.query("""
                SELECT u.user_id
                FROM users u JOIN roles r ON r.role_id=u.role_id
                WHERE lower(u.email)=lower(?) AND u.status='APPROVED'
                  AND u.deleted_at IS NULL AND u.must_change_password=FALSE
                  AND r.role_name='HORSE_OWNER'
                """, (row, rowNumber) -> row.getLong("user_id"), email);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public List<Map<String, Object>> findHorses(long ownerId) {
        return db.queryForList(HORSE_SELECT + """
                WHERE h.owner_id=? AND h.deleted_at IS NULL
                ORDER BY h.horse_name,h.id
                """, ownerId);
    }

    public Optional<Map<String, Object>> findOwnedHorse(String horseId, long ownerId) {
        List<Map<String, Object>> horses = db.queryForList(HORSE_SELECT + """
                WHERE h.id=? AND h.owner_id=? AND h.deleted_at IS NULL
                """, horseId, ownerId);
        return horses.stream().findFirst();
    }

    public List<Map<String, Object>> findHealthExams(String horseId) {
        return db.queryForList("""
                SELECT e.id,e.exam_date,e.temperature_c,e.heart_rate,e.respiratory_rate,
                       e.body_condition_score,e.gait_assessment,u.full_name AS doctor_name
                FROM health_exams e LEFT JOIN users u ON u.user_id=e.doctor_id
                WHERE e.horse_id=? ORDER BY e.exam_date DESC,e.id DESC
                """, horseId);
    }

    public List<Map<String, Object>> findTrainingMetrics(String horseId) {
        return db.queryForList("""
                SELECT m.id,m.training_schedule_id,m.body_weight_kg,m.max_heart_rate,
                       m.avg_speed_kmh,m.stamina_score,m.has_injury_alert,m.trainer_review,
                       m.is_simulated,m.recorded_at,u.full_name AS recorded_by_name
                FROM shared_training_metrics m
                LEFT JOIN users u ON u.user_id=m.recorded_by
                WHERE m.horse_id=?
                ORDER BY m.recorded_at DESC,m.id DESC
                """, horseId);
    }

    public List<Map<String, Object>> findTrainingSchedules(String horseId) {
        return db.queryForList("""
                SELECT ce.id,ce.event_type,ce.title,ce.event_date,ce.start_time,ce.end_time,
                       ce.status,ts.id AS training_schedule_id,ts.session_type,ts.track_surface,
                       tp.stage_name,g.full_name AS assigned_groom_name
                FROM calendar_events ce
                LEFT JOIN training_schedules ts
                  ON ce.source_table=? AND ce.source_id=ts.id
                LEFT JOIN training_plans tp ON tp.id=ts.training_plan_id
                LEFT JOIN users g ON g.user_id=ts.assigned_groom_id
                WHERE ce.horse_id=? AND ce.source_table=?
                ORDER BY ce.event_date DESC,ce.start_time DESC NULLS LAST,ce.id DESC
                """, CalendarEventSources.TRAINING_SCHEDULES, horseId, CalendarEventSources.TRAINING_SCHEDULES);
    }

    public List<Map<String, Object>> findTrainingVideos(String horseId) {
        return db.queryForList("""
                SELECT v.id,v.training_schedule_id,v.video_url,v.description,v.uploaded_at,
                       u.full_name AS uploaded_by_name,ts.session_type
                FROM training_videos v
                LEFT JOIN users u ON u.user_id=v.uploaded_by
                LEFT JOIN training_schedules ts ON ts.id=v.training_schedule_id
                WHERE v.horse_id=?
                ORDER BY v.uploaded_at DESC,v.id DESC
                """, horseId);
    }

    public List<Map<String, Object>> findRaceHistory(String horseId) {
        return db.queryForList("""
                SELECT e.id AS entry_id,e.status AS entry_status,e.result_position,e.prize_amount,
                       e.created_at,r.id AS race_id,r.race_name,r.grade,r.distance_category,
                       r.distance_meters,r.race_date,r.location,r.description,r.is_simulated
                FROM horse_race_entries e
                JOIN races r ON r.id=e.race_id
                WHERE e.horse_id=?
                ORDER BY r.race_date DESC,e.created_at DESC,e.id DESC
                """, horseId);
    }

    public List<Map<String, Object>> findFinancialTransactions(String horseId, int year) {
        return db.queryForList("""
                SELECT id,transaction_type,amount,billing_period,created_at
                FROM financial_reports
                WHERE horse_id=? AND billing_period>=? AND billing_period<=?
                ORDER BY billing_period DESC,created_at DESC,id DESC
                """, horseId, year + "-01", year + "-12");
    }
}
