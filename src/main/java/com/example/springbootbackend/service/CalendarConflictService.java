package com.example.springbootbackend.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/** Shared horse/person calendar conflict checks for veterinarian and trainer bookings. */
@Service
public class CalendarConflictService {
    private final VetData db;

    public CalendarConflictService(VetData db) {
        this.db = db;
    }

    public List<Map<String, Object>> checkCalendarConflicts(
            String horseId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            String excludeEventId,
            Long checkPersonId,
            String personRole
    ) {
        String personJoin = "";
        String personCondition = "";
        var personArgs = new ArrayList<Object>();
        if (checkPersonId != null && "VETERINARIAN".equals(personRole)) {
            personJoin = " LEFT JOIN periodic_care_schedules p ON ce.source_id=p.id AND ce.source_table='Periodic_Care_Schedules' ";
            personCondition = " OR p.assigned_doctor_id=?";
            personArgs.add(checkPersonId);
        } else if (checkPersonId != null && "HEAD_TRAINER".equals(personRole)) {
            personJoin = " LEFT JOIN training_schedules ts ON ce.source_id=ts.id AND ce.source_table='" + CalendarEventSources.TRAINING_SCHEDULES + "' ";
            personCondition = " OR ts.created_by=?";
            personArgs.add(checkPersonId);
        } else if (checkPersonId != null && "GROOM".equals(personRole)) {
            personJoin = " LEFT JOIN training_schedules ts ON ce.source_id=ts.id AND ce.source_table='" + CalendarEventSources.TRAINING_SCHEDULES + "' "
                    + " LEFT JOIN daily_task_logs dtl ON ce.source_id=dtl.id AND ce.source_table='daily_task_logs' ";
            personCondition = " OR ts.assigned_groom_id=? OR (dtl.groom_id=? AND dtl.status='Pending' AND ce.start_time IS NOT NULL AND ce.end_time IS NOT NULL)";
            personArgs.add(checkPersonId);
            personArgs.add(checkPersonId);
        }

        String overlap = startTime == null || endTime == null
                ? "TRUE"
                : "(ce.start_time IS NULL OR ce.end_time IS NULL OR (ce.start_time<? AND ce.end_time>?))";
        String sql = "SELECT DISTINCT ce.id AS event_id,ce.event_type,ce.title,ce.event_date,ce.start_time,ce.end_time,h.horse_name "
                + "FROM calendar_events ce JOIN horses h ON h.id=ce.horse_id " + personJoin
                + "WHERE h.deleted_at IS NULL AND ce.event_date=? AND ce.status NOT IN ('Cancelled','Completed') "
                + "AND ce.event_type<>'Rest' AND (ce.event_type<>'CareTask' OR (ce.start_time IS NOT NULL AND ce.end_time IS NOT NULL)) "
                + "AND (ce.horse_id=?" + personCondition + ") AND " + overlap;
        var args = new ArrayList<Object>();
        args.add(date);
        args.add(horseId);
        args.addAll(personArgs);
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
