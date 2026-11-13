package com.example.springbootbackend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.springbootbackend.entity.StableIncident;
import com.example.springbootbackend.exception.VetException;
import com.example.springbootbackend.repository.HorseImageUploadRepository;
import com.example.springbootbackend.repository.StableIncidentRepository;
import com.fasterxml.jackson.databind.JsonNode;

@Service
@Transactional(readOnly = true)
public class GroomWorkspaceService {
    private final VetData db;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final NotificationService notifications;
    private final GroomHorseService horses;
    private final HorseImageUploadRepository imageUploadRepository;
    private final StableIncidentRepository incidentRepository;

    public GroomWorkspaceService(
            VetData db,
            VetAccess access,
            VetAudit audit,
            VetTime time,
            NotificationService notifications,
            GroomHorseService horses,
            HorseImageUploadRepository imageUploadRepository,
            StableIncidentRepository incidentRepository) {
        this.db = db;
        this.access = access;
        this.audit = audit;
        this.time = time;
        this.notifications = notifications;
        this.horses = horses;
        this.imageUploadRepository = imageUploadRepository;
        this.incidentRepository = incidentRepository;
    }

    public long requireGroom(String email) { return access.requireRole(email, "GROOM"); }

    /** Backwards-compatible view, with the same least-privilege fields as the new routes. */
    public Map<String, Object> workspace(String email, LocalDate requestedDate) {
        LocalDate date = requestedDate == null ? time.today() : requestedDate;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);
        result.put("horses", horses.myHorses(email).get("data"));
        result.put("events", horses.calendar(email, date).get("events"));
        return result;
    }

    @Transactional
    public Map<String, Object> completeTask(String email, String ip, UUID taskId) {
        long groomId = access.requireRole(email, "GROOM");
        String id = taskId.toString();
        var rows = db.query("SELECT t.id,t.groom_id,t.status,t.task_type,ce.event_date,h.horse_name "
                        + "FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                        + "JOIN horses h ON h.id=t.horse_id WHERE t.id=? FOR UPDATE OF t",
                VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "TASK_NOT_FOUND", "Task not found");
        Map<String, Object> task = rows.getFirst();
        Object assignedGroom = task.get("groom_id");
        if (!(assignedGroom instanceof Number assigned) || assigned.longValue() != groomId)
            throw new VetException(403, "FORBIDDEN", "Task is not assigned to this Groom");
        if ("Completed".equals(task.get("status"))) {
            Map<String, Object> response = db.query("SELECT id,status,completed_at FROM daily_task_logs WHERE id=?",
                    VetRows.MAPPER, id).getFirst();
            response.put("already_completed", true);
            return response;
        }
        if (!"Pending".equals(task.get("status")))
            throw new VetException(409, "TASK_CANNOT_BE_COMPLETED", "Task is not pending");

        var taskDate = db.queryForObject("SELECT ce.event_date FROM daily_task_logs t "
                        + "JOIN calendar_events ce ON ce.id=t.calendar_event_id WHERE t.id=?",
                LocalDate.class, id);
        if (taskDate != null && taskDate.isAfter(time.today()))
            throw new VetException(409, "TASK_NOT_DUE", "A task can only be completed on or after its assigned date");
        db.update("UPDATE daily_task_logs SET status='Completed',completed_at=? WHERE id=?",
                time.utcNow(), id);
        db.update("UPDATE calendar_events SET status='Completed' WHERE source_table='daily_task_logs' AND source_id=?",
                id);
        audit.record(groomId, ip, "COMPLETE_GROOM_TASK:" + id);
        String message = task.get("task_type") + " care task completed for " + task.get("horse_name")
                + " on " + task.get("event_date") + ".";
        for (long manager : activeRecipients("CLUB_MANAGER"))
            notifications.schedule(manager, "Daily_Task_Logs", id, message, time.now());
        Map<String, Object> result = db.query("SELECT id,status,completed_at FROM daily_task_logs WHERE id=?",
                VetRows.MAPPER, id).getFirst();
        result.put("already_completed", false);
        return result;
    }

    @Transactional
    public Map<String, Object> completeTrainingSession(String email, String ip, UUID scheduleId) {
        long groomId = access.requireRole(email, "GROOM");
        String id = scheduleId.toString();
        var rows = db.query("SELECT ts.id,ts.horse_id,ts.calendar_event_id,ts.assigned_groom_id,ts.status,ts.session_type,ts.created_by,"
                        + "ce.event_date,ce.end_time,h.horse_name FROM training_schedules ts "
                        + "JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                        + "JOIN horses h ON h.id=ts.horse_id WHERE ts.id=? AND h.deleted_at IS NULL FOR UPDATE OF ts",
                VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "SCHEDULE_NOT_FOUND", "Training session not found");
        Map<String, Object> session = rows.getFirst();
        Object assignedGroom = session.get("assigned_groom_id");
        boolean assignedToCurrentGroom = assignedGroom instanceof Number assigned && assigned.longValue() == groomId;
        if (!assignedToCurrentGroom) {
            if ("Rest".equals(session.get("session_type")) && assignedGroom == null) {
                horses.requireAssignedHorse(groomId, session.get("horse_id").toString());
            } else {
                throw new VetException(403, "FORBIDDEN", "Training session is not assigned to this Groom");
            }
        }
        if ("Completed".equals(session.get("status"))) {
            Map<String, Object> response = db.query("SELECT id,status FROM training_schedules WHERE id=?",
                    VetRows.MAPPER, id).getFirst();
            response.put("already_completed", true);
            return response;
        }
        if (!List.of("Scheduled", "InProgress").contains(session.get("status")))
            throw new VetException(409, "SESSION_CANNOT_BE_COMPLETED", "Training session is not open for completion");

        if (!db.query("SELECT id FROM race_simulations WHERE training_schedule_id=? AND status='Running'", VetRows.MAPPER, id).isEmpty())
            throw new VetException(409, "SIMULATION_RUNNING", "Buoi tap dang thu du lieu; Head Trainer can luu ket qua gia lap truoc");
        if (!"Rest".equals(session.get("session_type")) && !"InProgress".equals(session.get("status")))
            throw new VetException(409, "SESSION_NOT_STARTED", "Buoi tap van dong phai duoc bat dau truoc khi xac nhan");
        LocalDate eventDate = (LocalDate) session.get("event_date");
        var endTime = (LocalTime) session.get("end_time");
        LocalDateTime scheduledEnd = LocalDateTime.of(eventDate,
                endTime == null ? LocalTime.MIDNIGHT : endTime);
        LocalDateTime now = LocalDateTime.ofInstant(time.now(), VetTime.BUSINESS_ZONE);
        if (scheduledEnd.isAfter(now))
            throw new VetException(409, "SESSION_NOT_OCCURRED", "Groom can confirm the session after its scheduled end time");

        db.update("UPDATE training_schedules SET status='Completed' WHERE id=?", id);
        db.update("UPDATE calendar_events SET status='Completed' WHERE id=?", session.get("calendar_event_id"));
        audit.record(groomId, ip, "COMPLETE_GROOM_TRAINING_SESSION:" + id);
        Object creator = session.get("created_by");
        if (creator instanceof Number trainerId && trainerId.longValue() != groomId)
            notifications.schedule(trainerId.longValue(), "Training_Schedules", id,
                    "Groom confirmed the training session for " + session.get("horse_name") + " on " + eventDate + ".",
                    time.now());
        Map<String, Object> response = db.query("SELECT id,status FROM training_schedules WHERE id=?",
                VetRows.MAPPER, id).getFirst();
        response.put("already_completed", false);
        return response;
    }

    @Transactional
    public Map<String, Object> reportIncident(String email, String ip, UUID horseId, JsonNode body) {
        long groomId = access.requireRole(email, "GROOM");
        horses.requireAssignedHorse(groomId, horseId.toString());
        Map<String, Object> fields = incidentFields(body);
        return insertIncident(groomId, ip, horseId.toString(), fields);
    }

    /** Legacy alias retained for existing clients; it applies the same assignment checks. */
    @Transactional
    public Map<String, Object> reportIncident(String email, String ip, JsonNode body) {
        Map<String, Object> fields = VetInput.parse(body,
                "horse_id:uuid", "issue_description:1000", "image_url:512", "is_emergency:bool");
        VetInput.required(fields, "horse_id");
        long groomId = access.requireRole(email, "GROOM");
        String horseId = fields.get("horse_id").toString();
        horses.requireAssignedHorse(groomId, horseId);
        fields.remove("horse_id");
        VetInput.required(fields, "issue_description");
        return insertIncident(groomId, ip, horseId, fields);
    }

    public Map<String, Object> incidents(String email, String status, UUID horseId) {
        long groomId = access.requireRole(email, "GROOM");
        String where = " WHERE (i.groom_id=? OR i.assigned_to=?)";
        List<Object> args = new ArrayList<>();
        args.add(groomId);
        args.add(groomId);
        args.add(groomId);
        if (status != null && !status.isBlank()) {
            where += " AND i.status=?";
            args.add(status.trim());
        }
        if (horseId != null) {
            where += " AND i.horse_id=?";
            args.add(horseId.toString());
        }
        var rows = db.query("SELECT i.id,i.horse_id,h.horse_name,i.groom_id,i.issue_description,i.image_url,"
                        + "i.status,i.is_emergency,i.assigned_to,i.assigned_role,a.full_name AS assignee_name,"
                        + "i.assignment_note,i.result_note,i.created_at,i.assigned_at,i.result_at,i.resolved_at,"
                        + "CASE WHEN i.assigned_to=? THEN TRUE ELSE FALSE END AS assigned_to_me "
                        + "FROM stable_incidents i JOIN horses h ON h.id=i.horse_id LEFT JOIN users a ON a.user_id=i.assigned_to" + where
                        + " ORDER BY i.created_at DESC,i.id DESC",
                VetRows.MAPPER, args.toArray());
        return Map.of("data", rows);
    }

    @Transactional
    public Map<String, Object> submitIncidentResult(String email, String ip, UUID incidentId, JsonNode body) {
        long groomId = access.requireRole(email, "GROOM");
        Map<String, Object> fields = VetInput.parse(body, "result_note:2000");
        VetInput.required(fields, "result_note");
        String id = incidentId.toString();
        var rows = db.query("SELECT i.id,i.status,i.assigned_to,i.groom_id,h.horse_name FROM stable_incidents i "
                        + "JOIN horses h ON h.id=i.horse_id WHERE i.id=? FOR UPDATE OF i",
                VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "INCIDENT_NOT_FOUND", "Incident not found");
        Map<String, Object> incident = rows.getFirst();
        Object assignedTo = incident.get("assigned_to");
        if (!(assignedTo instanceof Number assigned) || assigned.longValue() != groomId)
            throw new VetException(403, "FORBIDDEN", "Incident is not assigned to this Groom");
        if (!"InProgress".equals(incident.get("status")))
            throw new VetException(409, "INCIDENT_NOT_IN_PROGRESS", "Incident is not open for a result");

        db.update("UPDATE stable_incidents SET result_note=?,result_by=?,result_at=?,status='AwaitingClosure' WHERE id=?",
                fields.get("result_note"), groomId, time.utcNow(), id);
        audit.record(groomId, ip, "SUBMIT_STABLE_INCIDENT_RESULT:" + id);
        for (long manager : activeRecipients("CLUB_MANAGER"))
            notifications.schedule(manager, "Stable_Incidents", id,
                    "Incident result submitted for " + incident.get("horse_name") + "; Club Manager review is pending.", time.now());
        Object reporter = incident.get("groom_id");
        if (reporter instanceof Number recipient && recipient.longValue() != groomId)
            notifications.schedule(recipient.longValue(), "Stable_Incidents", id,
                    "A result was submitted for the incident reported on " + incident.get("horse_name") + ".", time.now());
        return Map.of("id", id, "status", "AwaitingClosure", "result_note", fields.get("result_note"));
    }

    private Map<String, Object> incidentFields(JsonNode body) {
        Map<String, Object> fields = VetInput.parse(body, "issue_description:1000", "image_url:512", "is_emergency:bool");
        VetInput.required(fields, "issue_description");
        return fields;
    }

    private Map<String, Object> insertIncident(long groomId, String ip, String horseId,
            Map<String, Object> fields) {
        Object image = fields.get("image_url");
        if (image instanceof String path) {
            if (!path.startsWith("incidents/"))
                throw VetException.invalid("image_url", "Upload an incident photo through the Groom workspace first");
            if (!imageUploadRepository.existsByObjectPathAndUploadedBy(path, groomId))
                throw VetException.invalid("image_url", "Upload this incident photo from your Groom account first");
        }
        String id = UUID.randomUUID().toString();
        boolean emergency = Boolean.TRUE.equals(fields.get("is_emergency"));

        StableIncident incident = new StableIncident();
        incident.setId(id);
        incident.setHorseId(horseId);
        incident.setGroomId(groomId);
        incident.setIssueDescription(fields.get("issue_description").toString());
        incident.setImageUrl(fields.get("image_url") != null ? fields.get("image_url").toString() : null);
        incident.setEmergency(emergency);
        incident.setStatus("Pending");
        incident.setCreatedAt(time.utcNow());
        incidentRepository.saveAndFlush(incident);

        audit.record(groomId, ip, "REPORT_STABLE_INCIDENT:" + id);
        var horse = db.query("SELECT horse_name,owner_id FROM horses WHERE id=?", VetRows.MAPPER, horseId).getFirst();
        String horseName = horse.get("horse_name").toString();
        String message = (emergency ? "URGENT: " : "") + "New Groom incident for " + horseName + ": " + fields.get("issue_description");
        for (long recipient : emergency ? activeRecipients("VETERINARIAN", "CLUB_MANAGER") : activeRecipients("CLUB_MANAGER"))
            notifications.schedule(recipient, "Stable_Incidents", id, message, time.now());
        List<Long> owners = db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                        + "WHERE u.user_id=? AND r.role_name='HORSE_OWNER' AND u.status='APPROVED' "
                        + "AND u.deleted_at IS NULL AND u.must_change_password=FALSE",
                Long.class, horse.get("owner_id"));
        for (long ownerId : owners)
            notifications.schedule(ownerId, "Stable_Incidents", id,
                    (emergency ? "URGENT: " : "") + "A Groom reported an incident involving your horse " + horseName + ". "
                            + fields.get("issue_description"), time.now());

        List<Long> trainers = db.queryForList("SELECT DISTINCT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                        + "WHERE r.role_name='HEAD_TRAINER' AND u.status='APPROVED' AND u.deleted_at IS NULL "
                        + "AND u.must_change_password=FALSE AND ("
                        + "EXISTS (SELECT 1 FROM training_plans tp JOIN training_plan_horses tph ON tph.training_plan_id=tp.id"
                        + " WHERE tph.horse_id=? AND tp.created_by=u.user_id "
                        + "AND (tp.start_date IS NULL OR tp.start_date<=?) AND (tp.end_date IS NULL OR tp.end_date>=?)) "
                        + "OR EXISTS (SELECT 1 FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                        + "WHERE ts.horse_id=? AND ts.created_by=u.user_id AND ts.status IN ('Scheduled','InProgress','Blocked') "
                        + "AND ce.status IN ('Scheduled','InProgress','Blocked') AND ce.event_date>=?)) ORDER BY u.user_id",
                Long.class, horseId, time.today(), time.today(), horseId, time.today());
        for (long trainerId : trainers)
            notifications.schedule(trainerId, "Stable_Incidents", id,
                    (emergency ? "URGENT: " : "") + "A stable incident was reported for " + horseName
                            + "; please review it because this horse has active training.", time.now());
        return db.query("SELECT id,horse_id,groom_id,issue_description,image_url,is_emergency,status,created_at "
                        + "FROM stable_incidents WHERE id=?",
                VetRows.MAPPER, id).getFirst();
    }

    private List<Long> activeRecipients(String... roles) {
        String placeholders = String.join(",", Collections.nCopies(roles.length, "?"));
        return db.query("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                        + "WHERE r.role_name IN (" + placeholders + ") AND u.status='APPROVED' "
                        + "AND u.deleted_at IS NULL AND u.must_change_password=FALSE ORDER BY u.user_id",
                (row, index) -> row.getLong("user_id"), (Object[]) roles);
    }
}
