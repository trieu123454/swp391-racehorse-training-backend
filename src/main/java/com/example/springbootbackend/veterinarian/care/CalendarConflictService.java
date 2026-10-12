package com.example.springbootbackend.veterinarian.care;

import com.example.springbootbackend.veterinarian.support.VetRows;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Shared horse/person calendar conflict checks for veterinarian and trainer bookings. */
@Service
public class CalendarConflictService {
    private final JdbcTemplate db;

    public CalendarConflictService(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> checkCalendarConflicts(String horseId, LocalDate date,
            LocalTime startTime, LocalTime endTime, String excludeEventId,
            Long checkPersonId, String personRole) {
        String personJoin = "";
        String personCondition = "";
        if (checkPersonId != null && "VETERINARIAN".equals(personRole)) {
            personJoin = " LEFT JOIN periodic_care_schedules p ON ce.source_id=p.id AND ce.source_table='Periodic_Care_Schedules' ";
            personCondition = " OR p.assigned_doctor_id=?";
        } else if (checkPersonId != null && "HEAD_TRAINER".equals(personRole)) {
            personJoin = " LEFT JOIN training_schedules ts ON ce.source_id=ts.id AND ce.source_table='" + CalendarEventSources.TRAINING_SCHEDULES + "' ";
            personCondition = " OR ts.created_by=?";
        } else if (checkPersonId != null && "GROOM".equals(personRole)) {
            personJoin = " LEFT JOIN training_schedules ts ON ce.source_id=ts.id AND ce.source_table='" + CalendarEventSources.TRAINING_SCHEDULES + "' ";
            personCondition = " OR ts.assigned_groom_id=?";
        }

        String overlap = startTime == null || endTime == null
                ? "TRUE"
                : "(ce.start_time IS NULL OR ce.end_time IS NULL OR (ce.start_time<? AND ce.end_time>?))";
        String sql = "SELECT DISTINCT ce.id AS event_id,ce.event_type,ce.event_date,ce.start_time,ce.end_time,h.horse_name "
                + "FROM calendar_events ce JOIN horses h ON h.id=ce.horse_id " + personJoin
                + "WHERE h.deleted_at IS NULL AND ce.event_date=? AND ce.status NOT IN ('Cancelled','Completed') "
                + "AND ce.event_type NOT IN ('CareTask','Rest') AND (ce.horse_id=?" + personCondition + ") AND " + overlap;
        var args = new ArrayList<Object>();
        args.add(date);
        args.add(horseId);
        if (checkPersonId != null && !personCondition.isEmpty()) args.add(checkPersonId);
        if (startTime != null && endTime != null) {
            args.add(endTime);
            args.add(startTime);
        }
        if (excludeEventId != null) {
            sql += " AND ce.id<>?";
            args.add(excludeEventId);
        }
        return db.query(sql + " ORDER BY ce.event_date,ce.start_time,ce.id", VetRows.MAPPER, args.toArray());
    }
}
