package com.example.springbootbackend.service;

import com.example.springbootbackend.dto.request.CreateStaffUserRequest;
import com.example.springbootbackend.dto.response.PendingUserPageResponse;
import com.example.springbootbackend.dto.response.PendingUserResponse;
import com.example.springbootbackend.dto.response.UserApprovalResponse;
import com.example.springbootbackend.entity.AuditLog;
import com.example.springbootbackend.entity.CalendarEvent;
import com.example.springbootbackend.entity.DailyTaskLog;
import com.example.springbootbackend.entity.FinancialReport;
import com.example.springbootbackend.entity.Notification;
import com.example.springbootbackend.entity.Race;
import com.example.springbootbackend.exception.VetException;
import com.example.springbootbackend.repository.AuditLogRepository;
import com.example.springbootbackend.repository.CalendarEventRepository;
import com.example.springbootbackend.repository.DailyTaskLogRepository;
import com.example.springbootbackend.repository.FinancialReportRepository;
import com.example.springbootbackend.repository.NotificationRepository;
import com.example.springbootbackend.repository.RaceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.*;

@Service
public class ClubManagerService {
    private static final List<String> STAFF_ROLES = List.of("HEAD_TRAINER", "VETERINARIAN", "GROOM");
    private static final List<String> CREATABLE_ROLES = List.of("HEAD_TRAINER", "VETERINARIAN", "GROOM", "CLUB_MANAGER");
    private static final List<String> ASSIGNABLE_ROLES = List.of("HEAD_TRAINER", "VETERINARIAN", "GROOM", "HORSE_OWNER");

    private final VetData db;
    private final PasswordEncoder passwordEncoder;
    private final VetTime time;
    private final AuditLogRepository auditLogRepository;
    private final NotificationRepository notificationRepository;
    private final FinancialReportRepository financialReportRepository;
    private final RaceRepository raceRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final DailyTaskLogRepository dailyTaskLogRepository;

    public ClubManagerService(VetData db, PasswordEncoder passwordEncoder, VetTime time,
                              AuditLogRepository auditLogRepository,
                              NotificationRepository notificationRepository,
                              FinancialReportRepository financialReportRepository,
                              RaceRepository raceRepository,
                              CalendarEventRepository calendarEventRepository,
                              DailyTaskLogRepository dailyTaskLogRepository) {
        this.db = db;
        this.passwordEncoder = passwordEncoder;
        this.time = time;
        this.auditLogRepository = auditLogRepository;
        this.notificationRepository = notificationRepository;
        this.financialReportRepository = financialReportRepository;
        this.raceRepository = raceRepository;
        this.calendarEventRepository = calendarEventRepository;
        this.dailyTaskLogRepository = dailyTaskLogRepository;
    }

    @Transactional
    public Map<String, Object> createStaffAccount(String managerEmail, CreateStaffUserRequest request) {
        long managerId = requireManager(managerEmail);
        String role = request.roleName().trim().toUpperCase(Locale.ROOT);
        if (!CREATABLE_ROLES.contains(role)) {
            throw new ResponseStatusException(BAD_REQUEST, "Vai trò tài khoản không hợp lệ");
        }
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte UTF-8");
        }

        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (db.queryForObject("SELECT count(*) FROM users WHERE lower(email)=lower(?)", Long.class, email) > 0) {
            throw new ResponseStatusException(CONFLICT, "Email đã được sử dụng");
        }

        Integer roleId = db.queryForObject("SELECT role_id FROM roles WHERE role_name=?", Integer.class, role);
        LocalDateTime now = time.utcNow();
        Long userId = db.queryForObject(
                "INSERT INTO users(full_name,email,phone,password_hash,role_id,status,approved_by,approved_at,must_change_password,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,'APPROVED',?,?,TRUE,?,?) RETURNING user_id",
                Long.class,
                request.fullName().trim(), email, request.phone(), passwordEncoder.encode(request.password()),
                roleId, managerId, now, now, now);

        audit(managerId, "CREATE_STAFF_ACCOUNT: " + userId + " " + role);
        return Map.of("id", userId, "email", email, "role_name", role, "status", "APPROVED", "must_change_password", true);
    }

    @Transactional(readOnly = true)
    public PendingUserPageResponse pendingUsers(String managerEmail, String role, int page, int limit) {
        requireManager(managerEmail);
        if (page < 1 || limit < 1 || limit > 100) {
            throw new ResponseStatusException(BAD_REQUEST, "page phải từ 1 và limit phải từ 1 đến 100");
        }
        String normalizedRole = normalizeStaffRole(role);
        String roleClause = normalizedRole == null ? "" : " AND r.role_name=?";
        String countSql = "SELECT count(*) FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE u.status='PENDING' AND u.deleted_at IS NULL AND r.role_name IN ('HEAD_TRAINER','VETERINARIAN','GROOM')"
                + roleClause;
        List<Object> countArgs = normalizedRole == null ? List.of() : List.of(normalizedRole);
        long total = db.queryForObject(countSql, Long.class, countArgs.toArray());
        String listSql = "SELECT u.user_id,u.full_name,u.email,u.phone,r.role_name,u.status,u.created_at "
                + "FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE u.status='PENDING' AND u.deleted_at IS NULL AND r.role_name IN ('HEAD_TRAINER','VETERINARIAN','GROOM')"
                + roleClause + " ORDER BY u.created_at ASC,u.user_id ASC LIMIT ? OFFSET ?";
        var args = new ArrayList<Object>(countArgs);
        args.add(limit);
        args.add((long) (page - 1) * limit);
        List<PendingUserResponse> data = db.queryForList(listSql, args.toArray()).stream()
                .map(ClubManagerService::toPendingUserResponse)
                .toList();
        return new PendingUserPageResponse(data, total, page, limit);
    }

    @Transactional(readOnly = true)
    public List<PendingUserResponse> users(String managerEmail, String status, String role) {
        requireManager(managerEmail);
        String requestedStatus = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "APPROVED";
        if (!List.of("APPROVED", "LOCKED", "REJECTED").contains(requestedStatus)) {
            throw new ResponseStatusException(BAD_REQUEST, "status không hợp lệ");
        }
        String normalizedRole = StringUtils.hasText(role) ? role.trim().toUpperCase(Locale.ROOT) : null;
        String roleClause = normalizedRole == null ? "" : " AND r.role_name=?";
        var args = new ArrayList<Object>();
        args.add(requestedStatus);
        if (normalizedRole != null) args.add(normalizedRole);
        return db.queryForList("SELECT u.user_id,u.full_name,u.email,u.phone,r.role_name,u.status,u.created_at "
                        + "FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.status=? AND u.deleted_at IS NULL"
                        + roleClause + " ORDER BY u.full_name,u.user_id", args.toArray()).stream()
                .map(ClubManagerService::toPendingUserResponse)
                .toList();
    }

    private static PendingUserResponse toPendingUserResponse(Map<String, Object> row) {
        Object createdAtObj = row.get("created_at");
        LocalDateTime createdAt;
        if (createdAtObj instanceof LocalDateTime ldt) {
            createdAt = ldt;
        } else if (createdAtObj instanceof Timestamp ts) {
            createdAt = ts.toLocalDateTime();
        } else if (createdAtObj instanceof Instant inst) {
            createdAt = LocalDateTime.ofInstant(inst, ZoneOffset.UTC);
        } else {
            createdAt = LocalDateTime.parse(createdAtObj.toString());
        }
        return new PendingUserResponse(
                ((Number) row.get("user_id")).longValue(),
                (String) row.get("full_name"),
                (String) row.get("email"),
                (String) row.get("phone"),
                (String) row.get("role_name"),
                (String) row.get("status"),
                createdAt);
    }

    public List<Map<String, Object>> activeGrooms(String managerEmail) {
        requireManager(managerEmail);
        return db.queryForList("SELECT u.user_id AS id,u.full_name,u.email FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE r.role_name='GROOM' AND u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL "
                + "ORDER BY u.full_name,u.user_id");
    }

    public Map<String, Object> incidentAssignees(String managerEmail) {
        requireManager(managerEmail);
        var rows = db.queryForList("SELECT u.user_id AS id,u.full_name,u.email,r.role_name FROM users u "
                + "JOIN roles r ON r.role_id=u.role_id WHERE r.role_name IN ('HEAD_TRAINER','VETERINARIAN','GROOM') "
                + "AND u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL "
                + "ORDER BY r.role_name,u.full_name,u.user_id");
        return Map.of("data", rows);
    }

    @Transactional
    public UserApprovalResponse approve(String managerEmail, Long userId) {
        long managerId = requireManager(managerEmail);
        Map<String, Object> user = findUser(userId);
        ensurePendingStaff(user);
        LocalDateTime now = time.utcNow();
        db.update("UPDATE users SET status='APPROVED', approved_by=?, approved_at=?, updated_at=? WHERE user_id=? AND status='PENDING'",
                managerId, now, now, userId);
        notifyUser(userId, "Tài khoản của bạn đã được Club Manager phê duyệt.");
        audit(managerId, "APPROVE_USER: " + userId);
        return new UserApprovalResponse(userId, "APPROVED", managerId, now);
    }

    @Transactional
    public Map<String, Object> reject(String managerEmail, Long userId, String reason) {
        long managerId = requireManager(managerEmail);
        Map<String, Object> user = findUser(userId);
        ensurePendingStaff(user);
        LocalDateTime now = time.utcNow();
        db.update("UPDATE users SET status='REJECTED', approved_by=?, approved_at=?, updated_at=? WHERE user_id=? AND status='PENDING'",
                managerId, now, now, userId);
        String message = StringUtils.hasText(reason)
                ? "Tài khoản bị từ chối. Lý do: " + reason.trim()
                : "Tài khoản của bạn đã bị từ chối bởi Club Manager.";
        notifyUser(userId, message);
        audit(managerId, "REJECT_USER: " + userId + (StringUtils.hasText(reason) ? " - " + reason.trim() : ""));
        return Map.of("id", userId, "status", "REJECTED");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> roleChangeRequests(String managerEmail, String status) {
        requireManager(managerEmail);
        String requestedStatus = StringUtils.hasText(status) ? status.trim() : "Pending";
        if (!List.of("Pending", "Approved", "Rejected").contains(requestedStatus)) {
            throw new ResponseStatusException(BAD_REQUEST, "status không hợp lệ");
        }
        return db.queryForList("SELECT r.id,r.user_id,u.full_name,u.email,cr.role_name AS current_role,rr.role_name AS requested_role,r.reason,r.status,r.created_at,r.reviewed_by,r.reviewed_at "
                + "FROM role_change_requests r JOIN users u ON u.user_id=r.user_id "
                + "LEFT JOIN roles cr ON cr.role_id=r.current_role_id JOIN roles rr ON rr.role_id=r.requested_role_id "
                + "WHERE r.status=? ORDER BY r.created_at ASC", requestedStatus);
    }

    @Transactional
    public Map<String, Object> handleRoleChange(String managerEmail, String requestId, String action) {
        long managerId = requireManager(managerEmail);
        String normalizedAction = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!normalizedAction.equals("approve") && !normalizedAction.equals("reject")) {
            throw new ResponseStatusException(BAD_REQUEST, "action phải là approve hoặc reject");
        }
        List<Map<String, Object>> rows = db.queryForList("SELECT id,user_id,requested_role_id,status FROM role_change_requests WHERE id=? FOR UPDATE", requestId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu đổi vai trò");
        Map<String, Object> request = rows.getFirst();
        if (!"Pending".equals(request.get("status"))) {
            throw new ResponseStatusException(CONFLICT, "Yêu cầu đổi vai trò đã được xử lý");
        }
        LocalDateTime now = time.utcNow();
        Long userId = ((Number) request.get("user_id")).longValue();
        if (normalizedAction.equals("approve")) {
            db.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE", userId);
            String currentRole = db.queryForObject("SELECT r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=?", String.class, userId);
            String requestedRole = db.queryForObject("SELECT role_name FROM roles WHERE role_id=?", String.class, request.get("requested_role_id"));
            if ("GROOM".equals(currentRole) && !"GROOM".equals(requestedRole))
                assertNoUpcomingGroomAssignments(userId);
            db.update("UPDATE users SET role_id=?, updated_at=? WHERE user_id=?", request.get("requested_role_id"), now, userId);
            db.update("UPDATE role_change_requests SET status='Approved',reviewed_by=?,reviewed_at=? WHERE id=?", managerId, now, requestId);
            notifyUser(userId, "Yêu cầu đổi vai trò của bạn đã được phê duyệt.");
        } else {
            db.update("UPDATE role_change_requests SET status='Rejected',reviewed_by=?,reviewed_at=? WHERE id=?", managerId, now, requestId);
            notifyUser(userId, "Yêu cầu đổi vai trò của bạn đã bị từ chối.");
        }
        audit(managerId, (normalizedAction.equals("approve") ? "APPROVE_ROLE_CHANGE: " : "REJECT_ROLE_CHANGE: ") + requestId);
        return Map.of("id", requestId, "status", normalizedAction.equals("approve") ? "Approved" : "Rejected", "reviewed_by", managerId, "reviewed_at", now);
    }

    @Transactional
    public Map<String, Object> lock(String managerEmail, Long userId, String reason) {
        long managerId = requireManager(managerEmail);
        Map<String, Object> user = findUser(userId);
        db.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE", userId);
        user = findUser(userId);
        String targetRole = (String) user.get("role_name");
        if (managerId == userId || "CLUB_MANAGER".equals(targetRole)) {
            throw new ResponseStatusException(FORBIDDEN, "Không thể khóa Club Manager hoặc chính tài khoản của bạn");
        }
        if (!"APPROVED".equals(user.get("status"))) {
            throw new ResponseStatusException(CONFLICT, "Chỉ được khóa tài khoản đã APPROVED");
        }
        if ("GROOM".equals(targetRole)) assertNoUpcomingGroomAssignments(userId);
        LocalDateTime now = time.utcNow();
        db.update("UPDATE users SET status='LOCKED', updated_at=? WHERE user_id=? AND status='APPROVED'", now, userId);
        String message = StringUtils.hasText(reason) ? "Tài khoản đã bị khóa. Lý do: " + reason.trim() : "Tài khoản của bạn đã bị khóa.";
        notifyUser(userId, message);
        audit(managerId, "LOCK_USER: " + userId + (StringUtils.hasText(reason) ? " - " + reason.trim() : ""));
        return Map.of("id", userId, "status", "LOCKED");
    }

    @Transactional
    public Map<String, Object> unlock(String managerEmail, Long userId) {
        long managerId = requireManager(managerEmail);
        db.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE", userId);
        Map<String, Object> user = findUser(userId);
        String targetRole = (String) user.get("role_name");
        if (managerId == userId || "CLUB_MANAGER".equals(targetRole)) {
            throw new ResponseStatusException(FORBIDDEN, "Không thể mở khóa Club Manager hoặc chính tài khoản của bạn");
        }
        if (!"LOCKED".equals(user.get("status"))) {
            throw new ResponseStatusException(CONFLICT, "Chỉ mở khóa được tài khoản đang LOCKED");
        }

        LocalDateTime now = time.utcNow();
        db.update("UPDATE users SET status='APPROVED', updated_at=? WHERE user_id=? AND status='LOCKED'", now, userId);
        notifyUser(userId, "Tài khoản của bạn đã được Club Manager mở khóa. Bạn có thể đăng nhập lại.");
        audit(managerId, "UNLOCK_USER: " + userId);
        return Map.of("id", userId, "status", "APPROVED");
    }

    @Transactional
    public Map<String, Object> updateRole(String managerEmail, Long userId, String roleName) {
        long managerId = requireManager(managerEmail);
        String normalizedRole = roleName == null ? "" : roleName.trim().toUpperCase(Locale.ROOT);
        if (!ASSIGNABLE_ROLES.contains(normalizedRole)) {
            throw new ResponseStatusException(BAD_REQUEST, "Vai trò không hợp lệ");
        }

        List<Map<String, Object>> rows = db.queryForList("SELECT u.user_id,u.status,r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=? AND u.deleted_at IS NULL FOR UPDATE", userId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy tài khoản");
        Map<String, Object> target = rows.getFirst();
        String currentRole = (String) target.get("role_name");
        if (managerId == userId || "CLUB_MANAGER".equals(currentRole)) {
            throw new ResponseStatusException(FORBIDDEN, "Không thể thay đổi vai trò của Club Manager hoặc chính tài khoản của bạn");
        }
        if (!"APPROVED".equals(target.get("status"))) {
            throw new ResponseStatusException(CONFLICT, "Chỉ được đổi vai trò tài khoản đang APPROVED");
        }
        if (normalizedRole.equals(currentRole)) {
            return Map.of("id", userId, "previous_role", currentRole, "role_name", currentRole);
        }

        if ("GROOM".equals(currentRole) && !"GROOM".equals(normalizedRole))
            assertNoUpcomingGroomAssignments(userId);
        Integer targetRoleId = db.queryForObject("SELECT role_id FROM roles WHERE role_name=?", Integer.class, normalizedRole);
        LocalDateTime now = time.utcNow();
        db.update("UPDATE users SET role_id=?,updated_at=? WHERE user_id=? AND status='APPROVED'", targetRoleId, now, userId);
        notifyUser(userId, "Vai trò của bạn đã được Club Manager đổi từ " + currentRole + " sang " + normalizedRole + ". Vui lòng đăng nhập lại để áp dụng quyền mới.");
        audit(managerId, "UPDATE_USER_ROLE: " + userId + " " + currentRole + " -> " + normalizedRole);
        return Map.of("id", userId, "previous_role", currentRole, "role_name", normalizedRole);
    }

    public Map<String, Object> operationsReport(String managerEmail, LocalDate from, LocalDate to) {
        requireManager(managerEmail);
        LocalDate start = from == null ? time.today() : from;
        LocalDate end = to == null ? start : to;
        validateReportRange(start, end);
        var health = db.queryForList("SELECT current_status,count(*) AS total FROM horses WHERE deleted_at IS NULL GROUP BY current_status ORDER BY current_status");
        var team = db.queryForList("SELECT r.role_name,count(*) AS total FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE u.status='APPROVED' AND u.deleted_at IS NULL "
                + "AND r.role_name IN ('HEAD_TRAINER','VETERINARIAN','GROOM','CLUB_MANAGER') "
                + "GROUP BY r.role_name ORDER BY r.role_name");
        Long scheduledSessions = db.queryForObject("SELECT count(*) FROM calendar_events WHERE event_type IN ('Training','TrialRun','Rest') "
                + "AND status IN ('Scheduled','InProgress','Blocked') AND event_date>=? AND event_date<=?", Long.class, start, end);
        Long totalTasks = db.queryForObject("SELECT count(*) FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                + "WHERE ce.event_date>=? AND ce.event_date<=?", Long.class, start, end);
        Long completedTasks = db.queryForObject("SELECT count(*) FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                + "WHERE ce.event_date>=? AND ce.event_date<=? AND t.status='Completed'", Long.class, start, end);
        Long pendingIncidents = db.queryForObject("SELECT count(*) FROM stable_incidents WHERE status IN ('Pending','InProgress','AwaitingClosure')", Long.class);
        Map<String, Object> training = db.queryForList("SELECT count(*) FILTER (WHERE ts.status='Scheduled') AS scheduled,"
                + "count(*) FILTER (WHERE ts.status='Completed') AS completed,"
                + "count(*) FILTER (WHERE ts.status='InProgress') AS in_progress,"
                + "count(*) FILTER (WHERE ts.status='Cancelled') AS cancelled,"
                + "count(*) FILTER (WHERE ts.status='Blocked') AS blocked FROM training_schedules ts "
                + "JOIN calendar_events ce ON ce.id=ts.calendar_event_id WHERE ce.event_date>=? AND ce.event_date<=?", start, end).getFirst();
        Map<String, Object> finances = db.queryForList("SELECT "
                + "COALESCE(SUM(amount) FILTER (WHERE lower(transaction_type)='care'),0) AS care_cost,"
                + "COALESCE(SUM(amount) FILTER (WHERE lower(transaction_type)='medical'),0) AS medical_cost,"
                + "COALESCE(SUM(amount) FILTER (WHERE lower(transaction_type)='prize'),0) AS prize_income,"
                + "COALESCE(SUM(amount) FILTER (WHERE lower(transaction_type)='other'),0) AS other_amount "
                + "FROM financial_reports WHERE billing_period>=? AND billing_period<=?",
                YearMonth.from(start).toString(), YearMonth.from(end).toString()).getFirst();
        Map<String, Object> competition = db.queryForList("SELECT count(*) FILTER (WHERE e.status='Registered') AS registered,"
                + "count(*) FILTER (WHERE e.status='Completed') AS completed "
                + "FROM horse_race_entries e JOIN races r ON r.id=e.race_id WHERE r.race_date>=? AND r.race_date<=? "
                + "AND r.is_simulated=FALSE", start, end).getFirst();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", start); result.put("to", end); result.put("horses_by_health_status", health);
        result.put("approved_staff_by_role", team); result.put("scheduled_training_sessions", scheduledSessions);
        result.put("groom_tasks", Map.of("total", totalTasks, "completed", completedTasks));
        result.put("pending_incidents", pendingIncidents);
        result.put("training_performance", training);
        result.put("operating_costs_and_prize_income", finances);
        result.put("official_competition", competition);
        return result;
    }

    public Map<String, Object> officialRaces(String managerEmail) {
        requireManager(managerEmail);
        var rows = db.query("SELECT r.id,r.race_name,r.grade,r.distance_category,r.distance_meters,r.race_date,r.location,r.description "
                        + "FROM races r WHERE r.is_simulated=FALSE AND NOT EXISTS "
                        + "(SELECT 1 FROM race_simulations sim WHERE sim.race_id=r.id) ORDER BY r.race_date,r.race_name,r.id",
                VetRows.MAPPER);
        return Map.of("data", rows);
    }

    @Transactional
    public Map<String, Object> createOfficialRace(String managerEmail, String ip, JsonNode body) {
        long managerId = requireManager(managerEmail);
        var fields = VetInput.parse(body, "race_name:150", "grade:10", "distance_category:20",
                "distance_meters:number", "race_date:date", "location:150", "description:0");
        VetInput.required(fields, "race_name", "race_date");
        String raceName = fields.get("race_name").toString().trim();
        if (raceName.isEmpty()) throw VetException.invalid("race_name", "Race name is required");
        LocalDate raceDate = (LocalDate) fields.get("race_date");
        if (raceDate.isBefore(time.today())) throw VetException.invalid("race_date", "Official races must be scheduled for today or later");
        Integer distance = null;
        if (fields.get("distance_meters") != null) {
            BigDecimal value = (BigDecimal) fields.get("distance_meters");
            try { distance = value.intValueExact(); }
            catch (ArithmeticException ex) { throw VetException.invalid("distance_meters", "Distance must be a whole number of meters"); }
            if (distance < 1 || distance > 100000) throw VetException.invalid("distance_meters", "Distance must be between 1 and 100000 meters");
        }
        String id = UUID.randomUUID().toString();
        Race race = new Race();
        race.setId(id);
        race.setRaceName(raceName);
        race.setGrade(fields.get("grade") == null ? null : fields.get("grade").toString());
        race.setDistanceCategory(fields.get("distance_category") == null ? null : fields.get("distance_category").toString());
        race.setDistanceMeters(distance);
        race.setRaceDate(raceDate);
        race.setLocation(fields.get("location") == null ? null : fields.get("location").toString());
        race.setDescription(fields.get("description") == null ? null : fields.get("description").toString());
        race.setSimulated(false);
        raceRepository.saveAndFlush(race);
        audit(managerId, "CREATE_OFFICIAL_RACE:" + id, ip);
        return db.query("SELECT id,race_name,grade,distance_category,distance_meters,race_date,location,description "
                + "FROM races WHERE id=?", VetRows.MAPPER, id).getFirst();
    }

    public Map<String, Object> auditLogs(String managerEmail, LocalDate from, LocalDate to, int page, int limit) {
        requireManager(managerEmail);
        validatePage(page, limit);
        if (from != null && to != null && to.isBefore(from)) throw new ResponseStatusException(BAD_REQUEST, "to must be on or after from");
        String where = " WHERE 1=1"; List<Object> args = new ArrayList<>();
        if (from != null) { where += " AND a.created_at>=?"; args.add(time.utcStartOf(from)); }
        if (to != null) { where += " AND a.created_at<?"; args.add(time.utcStartOf(to.plusDays(1))); }
        Long total = db.queryForObject("SELECT count(*) FROM audit_logs a" + where, Long.class, args.toArray());
        var queryArgs = new ArrayList<>(args); queryArgs.add(limit); queryArgs.add((long) (page - 1) * limit);
        var rows = db.query("SELECT a.id,a.user_id,u.full_name AS user_name,u.email,a.action_performed,a.ip_address,a.created_at "
                + "FROM audit_logs a LEFT JOIN users u ON u.user_id=a.user_id" + where
                + " ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?", VetRows.MAPPER, queryArgs.toArray());
        return Map.of("data", rows, "total", total, "page", page, "limit", limit);
    }

    @Transactional
    public Map<String, Object> assignGroomTask(String managerEmail, String ip, JsonNode body) {
        long managerId = requireManager(managerEmail);
        var fields = VetInput.parse(body, "horse_id:uuid", "groom_id:long", "task_type:30", "task_date:date");
        VetInput.required(fields, "horse_id", "groom_id", "task_type", "task_date");
        String horseId = fields.get("horse_id").toString(); long groomId = ((Number) fields.get("groom_id")).longValue();
        String taskType = fields.get("task_type").toString(); LocalDate taskDate = (LocalDate) fields.get("task_date");
        if (!List.of("Feeding", "Cleaning", "Bathing", "IceBath").contains(taskType))
            throw VetException.invalid("task_type", "Values: Feeding, Cleaning, Bathing, IceBath");
        if (taskDate.isBefore(time.today().minusDays(1)) || taskDate.isAfter(time.today().plusDays(60)))
            throw VetException.invalid("task_date", "Task date must be within the next 60 days");
        if (db.queryForObject("SELECT count(*) FROM horses WHERE id=? AND deleted_at IS NULL", Long.class, horseId) == 0)
            throw new ResponseStatusException(NOT_FOUND, "Horse not found");
        var grooms = db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE u.user_id=? AND r.role_name='GROOM' AND u.status='APPROVED' AND u.must_change_password=FALSE "
                + "AND u.deleted_at IS NULL FOR UPDATE OF u", groomId);
        if (grooms.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Groom account is not active");
        Long duplicate = db.queryForObject("SELECT count(*) FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                + "WHERE t.horse_id=? AND t.groom_id=? AND t.task_type=? AND ce.event_date=?", Long.class, horseId, groomId, taskType, taskDate);
        if (duplicate != null && duplicate > 0) throw new ResponseStatusException(CONFLICT, "This task is already assigned for that horse and date");
        String id = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        CalendarEvent event = new CalendarEvent();
        event.setId(eventId);
        event.setHorseId(horseId);
        event.setEventType("CareTask");
        event.setTitle(taskType);
        event.setEventDate(taskDate);
        event.setStatus("Scheduled");
        event.setSourceTable("daily_task_logs");
        event.setSourceId(id);
        event.setCreatedBy(managerId);
        calendarEventRepository.saveAndFlush(event);

        DailyTaskLog task = new DailyTaskLog();
        task.setId(id);
        task.setCalendarEventId(eventId);
        task.setHorseId(horseId);
        task.setGroomId(groomId);
        task.setAssignedBy(managerId);
        task.setTaskType(taskType);
        task.setStatus("Pending");
        dailyTaskLogRepository.saveAndFlush(task);

        audit(managerId, "ASSIGN_GROOM_TASK: " + id + " to " + groomId, ip);
        notifyUser(groomId, taskType + " task assigned for " + taskDate + ".");
        return db.query("SELECT t.id,t.horse_id,h.horse_name,t.groom_id,u.full_name AS groom_name,t.task_type,ce.event_date AS task_date,t.status "
                + "FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id JOIN horses h ON h.id=t.horse_id "
                + "LEFT JOIN users u ON u.user_id=t.groom_id WHERE t.id=?", VetRows.MAPPER, id).getFirst();
    }

    public Map<String, Object> groomTasks(String managerEmail, LocalDate date) {
        requireManager(managerEmail);
        LocalDate selected = date == null ? time.today() : date;
        var rows = db.query("SELECT t.id,t.horse_id,h.horse_name,t.groom_id,u.full_name AS groom_name,t.task_type,ce.event_date AS task_date,t.status,t.completed_at "
                + "FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id JOIN horses h ON h.id=t.horse_id "
                + "LEFT JOIN users u ON u.user_id=t.groom_id WHERE ce.event_date=? "
                + "ORDER BY CASE WHEN t.status='Pending' THEN 0 ELSE 1 END,u.full_name,h.horse_name,t.id", VetRows.MAPPER, selected);
        return Map.of("date", selected, "data", rows);
    }

    @Transactional
    public Map<String, Object> updateGroomTask(String managerEmail, String ip, UUID taskId, JsonNode body) {
        long managerId = requireManager(managerEmail); String id = taskId.toString();
        var rows = db.query("SELECT t.id,t.groom_id,t.status,ce.id AS event_id,ce.event_date "
                + "FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                + "WHERE t.id=? FOR UPDATE OF t,ce", VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Groom task not found");
        var task = rows.getFirst();
        if (!"Pending".equals(task.get("status"))) throw new ResponseStatusException(CONFLICT, "Only a pending Groom task can be reassigned or cancelled");
        Long previousGroom = task.get("groom_id") instanceof Number value ? value.longValue() : null;
        var fields = VetInput.parse(body, "action:10", "groom_id:long");
        if (fields.containsKey("action")) {
            String action = fields.get("action").toString();
            if (!"Cancel".equals(action) || fields.containsKey("groom_id"))
                throw VetException.invalid("action", "Use Cancel alone, or send groom_id to reassign");
            db.update("UPDATE daily_task_logs SET status='Cancelled' WHERE id=?", id);
            db.update("UPDATE calendar_events SET status='Cancelled' WHERE id=?", task.get("event_id"));
            if (previousGroom != null) notifyUser(previousGroom, "A care task scheduled for " + task.get("event_date") + " was cancelled.");
            audit(managerId, "CANCEL_GROOM_TASK: " + id, ip);
        } else {
            VetInput.required(fields, "groom_id");
            long groomId = ((Number) fields.get("groom_id")).longValue();
            var grooms = db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                    + "WHERE u.user_id=? AND r.role_name='GROOM' AND u.status='APPROVED' "
                    + "AND u.must_change_password=FALSE AND u.deleted_at IS NULL FOR UPDATE OF u", groomId);
            if (grooms.isEmpty()) throw VetException.invalid("groom_id", "Groom account is not active");
            db.update("UPDATE daily_task_logs SET groom_id=?,assigned_by=? WHERE id=?", groomId, managerId, id);
            if (previousGroom != null && previousGroom != groomId)
                notifyUser(previousGroom, "A care task scheduled for " + task.get("event_date") + " was reassigned.");
            notifyUser(groomId, "A care task was assigned for " + task.get("event_date") + ".");
            audit(managerId, "REASSIGN_GROOM_TASK: " + id + " to " + groomId, ip);
        }
        return db.query("SELECT t.id,t.horse_id,h.horse_name,t.groom_id,u.full_name AS groom_name,t.task_type,"
                + "ce.event_date AS task_date,t.status FROM daily_task_logs t "
                + "JOIN calendar_events ce ON ce.id=t.calendar_event_id JOIN horses h ON h.id=t.horse_id "
                + "LEFT JOIN users u ON u.user_id=t.groom_id WHERE t.id=?", VetRows.MAPPER, id).getFirst();
    }

    public Map<String, Object> incidents(String managerEmail, String status) {
        requireManager(managerEmail);
        String selected = StringUtils.hasText(status) ? status.trim() : "Open";
        if (!List.of("Open", "Pending", "InProgress", "AwaitingClosure", "Resolved", "All").contains(selected))
            throw new ResponseStatusException(BAD_REQUEST, "status must be Open, Pending, InProgress, AwaitingClosure, Resolved, or All");
        String filter = switch (selected) {
            case "Open" -> "WHERE i.status IN ('Pending','InProgress','AwaitingClosure')";
            case "All" -> "";
            default -> "WHERE i.status=?";
        };
        var rows = "Open".equals(selected) || "All".equals(selected)
                ? db.query("SELECT i.id,i.horse_id,h.horse_name,i.groom_id,g.full_name AS groom_name,i.issue_description,"
                    + "i.image_url,i.is_emergency,i.status,i.assigned_to,i.assigned_role,a.full_name AS assignee_name,"
                    + "i.assignment_note,i.assigned_by,i.assigned_at,i.result_note,i.result_by,i.result_at,"
                    + "i.reviewed_by,r.full_name AS reviewer_name,i.created_at,i.resolved_at "
                    + "FROM stable_incidents i JOIN horses h ON h.id=i.horse_id LEFT JOIN users g ON g.user_id=i.groom_id "
                    + "LEFT JOIN users a ON a.user_id=i.assigned_to LEFT JOIN users r ON r.user_id=i.reviewed_by "
                    + filter + " ORDER BY i.is_emergency DESC,i.created_at DESC,i.id", VetRows.MAPPER)
                : db.query("SELECT i.id,i.horse_id,h.horse_name,i.groom_id,g.full_name AS groom_name,i.issue_description,"
                    + "i.image_url,i.is_emergency,i.status,i.assigned_to,i.assigned_role,a.full_name AS assignee_name,"
                    + "i.assignment_note,i.assigned_by,i.assigned_at,i.result_note,i.result_by,i.result_at,"
                    + "i.reviewed_by,r.full_name AS reviewer_name,i.created_at,i.resolved_at "
                    + "FROM stable_incidents i JOIN horses h ON h.id=i.horse_id LEFT JOIN users g ON g.user_id=i.groom_id "
                    + "LEFT JOIN users a ON a.user_id=i.assigned_to LEFT JOIN users r ON r.user_id=i.reviewed_by "
                    + filter + " ORDER BY i.is_emergency DESC,i.created_at DESC,i.id", VetRows.MAPPER, selected);
        return Map.of("data", rows);
    }

    @Transactional
    public Map<String, Object> assignIncident(String managerEmail, String ip, UUID incidentId, JsonNode body) {
        long managerId = requireManager(managerEmail); String id = incidentId.toString();
        var fields = VetInput.parse(body, "assigned_role:20", "assignee_id:long", "assignment_note:1000");
        VetInput.choice(fields, "assigned_role", "HEAD_TRAINER", "VETERINARIAN", "GROOM", "CLUB_MANAGER");
        VetInput.required(fields, "assignment_note");
        String role = fields.get("assigned_role").toString();
        long assigneeId;
        if ("CLUB_MANAGER".equals(role)) {
            if (fields.containsKey("assignee_id")) throw VetException.invalid("assignee_id", "Club Manager assignment is the current manager");
            assigneeId = managerId;
        } else {
            VetInput.required(fields, "assignee_id");
            assigneeId = ((Number) fields.get("assignee_id")).longValue();
            Long valid = db.queryForObject("SELECT count(*) FROM users u JOIN roles r ON r.role_id=u.role_id "
                    + "WHERE u.user_id=? AND r.role_name=? AND u.status='APPROVED' AND u.must_change_password=FALSE "
                    + "AND u.deleted_at IS NULL", Long.class, assigneeId, role);
            if (valid == null || valid == 0) throw VetException.invalid("assignee_id", "Assignee account is not active");
        }
        var rows = db.query("SELECT i.id,i.status,i.assigned_to,i.groom_id,h.horse_name FROM stable_incidents i "
                        + "JOIN horses h ON h.id=i.horse_id WHERE i.id=? FOR UPDATE OF i", VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Incident not found");
        Map<String, Object> incident = rows.getFirst();
        if (!List.of("Pending", "InProgress").contains(incident.get("status")))
            throw new ResponseStatusException(CONFLICT, "Only a pending or active incident can be assigned");
        Object previous = incident.get("assigned_to");
        db.update("UPDATE stable_incidents SET status='InProgress',assigned_to=?,assigned_role=?,assignment_note=?,"
                        + "assigned_by=?,assigned_at=?,reviewed_by=?,result_note=NULL,result_by=NULL,result_at=NULL WHERE id=?",
                assigneeId, role, fields.get("assignment_note"), managerId, time.utcNow(), managerId, id);
        String horse = (String) incident.get("horse_name");
        String msg = "Incident for " + horse + " assigned to you. Instructions: " + fields.get("assignment_note");
        notifyUser(assigneeId, msg);
        if (previous instanceof Number old && old.longValue() != assigneeId) notifyUser(old.longValue(), "Incident for " + horse + " was reassigned.");
        if (incident.get("groom_id") instanceof Number reporter && reporter.longValue() != assigneeId)
            notifyUser(reporter.longValue(), "Incident for " + horse + " is assigned to " + role + ". Instructions: " + fields.get("assignment_note"));
        audit(managerId, "ASSIGN_STABLE_INCIDENT: " + id + " to " + role + ":" + assigneeId, ip);
        return Map.of("id", id, "status", "InProgress", "assigned_to", assigneeId, "assigned_role", role,
                "assignment_note", fields.get("assignment_note"));
    }

    @Transactional
    public Map<String, Object> submitManagerIncidentResult(String managerEmail, String ip, UUID incidentId, JsonNode body) {
        long managerId = requireManager(managerEmail); String id = incidentId.toString();
        var fields = VetInput.parse(body, "result_note:2000"); VetInput.required(fields, "result_note");
        var rows = db.query("SELECT i.id,i.status,i.assigned_to,i.groom_id,h.horse_name FROM stable_incidents i "
                        + "JOIN horses h ON h.id=i.horse_id WHERE i.id=? FOR UPDATE OF i", VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Incident not found");
        Map<String, Object> incident = rows.getFirst();
        if (!"InProgress".equals(incident.get("status")) || !(incident.get("assigned_to") instanceof Number assigned)
                || assigned.longValue() != managerId)
            throw new ResponseStatusException(CONFLICT, "Only the Manager assigned to this incident can record its result");
        db.update("UPDATE stable_incidents SET result_note=?,result_by=?,result_at=?,status='AwaitingClosure' WHERE id=?",
                fields.get("result_note"), managerId, time.utcNow(), id);
        audit(managerId, "SUBMIT_STABLE_INCIDENT_RESULT: " + id, ip);
        Object reporter = incident.get("groom_id");
        if (reporter instanceof Number recipient) notifyUser(recipient.longValue(), "A result was recorded for the incident on " + incident.get("horse_name") + ".");
        return Map.of("id", id, "status", "AwaitingClosure", "result_note", fields.get("result_note"));
    }

    @Transactional
    public Map<String, Object> resolveIncident(String managerEmail, String ip, UUID incidentId) {
        long managerId = requireManager(managerEmail); String id = incidentId.toString();
        var rows = db.queryForList("SELECT i.id,i.status,i.groom_id,i.assigned_to,i.result_note,h.horse_name FROM stable_incidents i "
                + "JOIN horses h ON h.id=i.horse_id WHERE i.id=? FOR UPDATE OF i", id);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Incident not found");
        if (!"AwaitingClosure".equals(rows.getFirst().get("status"))) throw new ResponseStatusException(CONFLICT, "Incident must have a recorded result before it can be closed");
        if (rows.getFirst().get("result_note") == null) throw new ResponseStatusException(CONFLICT, "Incident result is required before closing");
        db.update("UPDATE stable_incidents SET status='Resolved',reviewed_by=?,resolved_at=? WHERE id=?", managerId, time.utcNow(), id);
        Object groomId = rows.getFirst().get("groom_id");
        if (groomId instanceof Number recipient)
            notifyUser(recipient.longValue(), "Your incident report for " + rows.getFirst().get("horse_name") + " has been resolved.");
        Object assignee = rows.getFirst().get("assigned_to");
        if (assignee instanceof Number recipient && (!(groomId instanceof Number reporter) || reporter.longValue() != recipient.longValue()))
            notifyUser(recipient.longValue(), "The incident assigned to you has been closed.");
        audit(managerId, "RESOLVE_STABLE_INCIDENT: " + id, ip);
        return Map.of("id", id, "status", "Resolved");
    }

    @Transactional
    public Map<String, Object> addFinancialTransaction(String managerEmail, String ip, JsonNode body) {
        long managerId = requireManager(managerEmail);
        var fields = VetInput.parse(body, "horse_id:uuid", "transaction_type:30", "amount:number", "billing_period:7");
        VetInput.required(fields, "horse_id", "transaction_type", "amount", "billing_period");
        String horseId = fields.get("horse_id").toString(); String type = fields.get("transaction_type").toString();
        BigDecimal amount = (BigDecimal) fields.get("amount"); String period = fields.get("billing_period").toString();
        if (!List.of("Care", "Medical", "Prize", "Other").contains(type)) throw VetException.invalid("transaction_type", "Values: Care, Medical, Prize, Other");
        if (amount.signum() <= 0 || amount.scale() > 2) throw VetException.invalid("amount", "Must be greater than zero with at most two decimals");
        if (!period.matches("\\d{4}-(0[1-9]|1[0-2])")) throw VetException.invalid("billing_period", "Expected YYYY-MM");
        if (db.queryForObject("SELECT count(*) FROM horses WHERE id=? AND deleted_at IS NULL", Long.class, horseId) == 0)
            throw new ResponseStatusException(NOT_FOUND, "Horse not found");
        String id = UUID.randomUUID().toString();
        FinancialReport report = new FinancialReport();
        report.setId(id);
        report.setHorseId(horseId);
        report.setTransactionType(type);
        report.setAmount(amount);
        report.setBillingPeriod(period);
        report.setCreatedAt(time.utcNow());
        financialReportRepository.saveAndFlush(report);
        audit(managerId, "CREATE_FINANCIAL_TRANSACTION: " + id + " for " + horseId, ip);
        return Map.of("id", id, "horse_id", horseId, "transaction_type", type, "amount", amount, "billing_period", period);
    }

    private void validateReportRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new ResponseStatusException(BAD_REQUEST, "to must be on or after from");
        if (from.isBefore(time.today().minusYears(5)) || to.isAfter(time.today().plusDays(60)))
            throw new ResponseStatusException(BAD_REQUEST, "Report dates must be within the last five years and next 60 days");
    }

    private void validatePage(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 100) throw new ResponseStatusException(BAD_REQUEST, "page must be >=1 and limit 1-100");
    }

    private long requireManager(String email) {
        List<Map<String, Object>> rows = db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id WHERE lower(u.email)=lower(?) AND r.role_name='CLUB_MANAGER' AND u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL", email);
        if (rows.isEmpty()) throw new ResponseStatusException(FORBIDDEN, "Chỉ Club Manager đã được duyệt mới được thao tác");
        return ((Number) rows.getFirst().get("user_id")).longValue();
    }

    private Map<String, Object> findUser(Long userId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT u.user_id,u.status,r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=? AND u.deleted_at IS NULL", userId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy tài khoản");
        return rows.getFirst();
    }

    private void assertNoUpcomingGroomAssignments(Long userId) {
        Long sessions = db.queryForObject("SELECT count(*) FROM training_schedules ts "
                + "JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                + "WHERE ts.assigned_groom_id=? AND ts.status IN ('Scheduled','Blocked') "
                + "AND ce.event_date>=?", Long.class, userId, time.today());
        Long careTasks = db.queryForObject("SELECT count(*) FROM daily_task_logs t "
                + "JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                + "WHERE t.groom_id=? AND t.status='Pending' AND ce.status<>'Cancelled' "
                + "AND ce.event_date>=?", Long.class, userId, time.today());
        long total = (sessions == null ? 0 : sessions) + (careTasks == null ? 0 : careTasks);
        if (total > 0)
            throw new ResponseStatusException(CONFLICT,
                    "Phai phan cong lai hoac huy " + total + " buoi tap/cong viec sap toi truoc khi khoa/doi vai tro Groom");
    }

    private void ensurePendingStaff(Map<String, Object> user) {
        if (!"PENDING".equals(user.get("status"))) throw new ResponseStatusException(CONFLICT, "Tài khoản không ở trạng thái PENDING");
        if (!STAFF_ROLES.contains(user.get("role_name"))) throw new ResponseStatusException(CONFLICT, "Horse Owner không thuộc danh sách chờ duyệt");
    }

    private String normalizeStaffRole(String role) {
        if (!StringUtils.hasText(role)) return null;
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!STAFF_ROLES.contains(normalized)) throw new ResponseStatusException(BAD_REQUEST, "role chỉ nhận HEAD_TRAINER, VETERINARIAN hoặc GROOM");
        return normalized;
    }

    private void notifyUser(Long userId, String message) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID().toString());
        notification.setUserId(userId);
        notification.setRelatedTable("users");
        notification.setRelatedId(String.valueOf(userId));
        notification.setMessage(message);
        notification.setRead(false);
        notification.setCreatedAt(time.utcNow());
        notificationRepository.saveAndFlush(notification);
    }

    private void audit(long managerId, String action) {
        AuditLog log = new AuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setUserId(managerId);
        log.setActionPerformed(action);
        log.setCreatedAt(time.utcNow());
        auditLogRepository.saveAndFlush(log);
    }

    private void audit(long managerId, String action, String ip) {
        AuditLog log = new AuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setUserId(managerId);
        log.setActionPerformed(action);
        log.setIpAddress(ip);
        log.setCreatedAt(time.utcNow());
        auditLogRepository.saveAndFlush(log);
    }
}
