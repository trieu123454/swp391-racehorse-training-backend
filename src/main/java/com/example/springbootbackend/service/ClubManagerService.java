package com.example.springbootbackend.service;

import static com.example.springbootbackend.service.NotificationContent.Type.*;
import static org.springframework.http.HttpStatus.*;

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
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Staff, horse registration, inventory, official races, and reporting for Club Managers. */
@Service
public class ClubManagerService {
  private static final List<String> STAFF_ROLES =
      List.of("HEAD_TRAINER", "VETERINARIAN", "HEAD_VETERINARIAN", "GROOM");
  private static final List<String> CREATABLE_ROLES =
      List.of("HEAD_TRAINER", "VETERINARIAN", "HEAD_VETERINARIAN", "GROOM", "CLUB_MANAGER");
  private static final List<String> ASSIGNABLE_ROLES =
      List.of("HEAD_TRAINER", "VETERINARIAN", "HEAD_VETERINARIAN", "GROOM", "HORSE_OWNER");
  private final VetData db;
  private final NotificationService notifications;
  private final PasswordEncoder passwordEncoder;
  private final VetTime time;
  private final AuditLogRepository auditLogRepository;
  private final NotificationRepository notificationRepository;
  private final FinancialReportRepository financialReportRepository;
  private final RaceRepository raceRepository;
  private final CalendarEventRepository calendarEventRepository;
  private final DailyTaskLogRepository dailyTaskLogRepository;
  private final EmailService emailService;
  private final SmsService smsService;
  private final CalendarConflictService conflicts;

  public ClubManagerService(
      VetData db,
      NotificationService notifications,
      PasswordEncoder passwordEncoder,
      VetTime time,
      AuditLogRepository auditLogRepository,
      NotificationRepository notificationRepository,
      FinancialReportRepository financialReportRepository,
      RaceRepository raceRepository,
      CalendarEventRepository calendarEventRepository,
      DailyTaskLogRepository dailyTaskLogRepository,
      EmailService emailService,
      SmsService smsService,
      CalendarConflictService conflicts) {
    this.db = db;
    this.notifications = notifications;
    this.passwordEncoder = passwordEncoder;
    this.time = time;
    this.auditLogRepository = auditLogRepository;
    this.notificationRepository = notificationRepository;
    this.financialReportRepository = financialReportRepository;
    this.raceRepository = raceRepository;
    this.calendarEventRepository = calendarEventRepository;
    this.dailyTaskLogRepository = dailyTaskLogRepository;
    this.emailService = emailService;
    this.smsService = smsService;
    this.conflicts = conflicts;
  }

  @Transactional
  public Map<String, Object> createStaffAccount(
      String managerEmail, CreateStaffUserRequest request) {
    long managerId = requireManager(managerEmail);
    String role = request.roleName().trim().toUpperCase(Locale.ROOT);
    if (!CREATABLE_ROLES.contains(role)) {
      throw new ResponseStatusException(BAD_REQUEST, "Vai trò tài khoản không hợp lệ");
    }
    if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
      throw new ResponseStatusException(BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte UTF-8");
    }
    String email = request.email().trim().toLowerCase(Locale.ROOT);
    if (db.queryForObject(
            "SELECT count(*) FROM users WHERE lower(email)=lower(?)", Long.class, email)
        > 0) {
      throw new ResponseStatusException(CONFLICT, "Email đã được sử dụng");
    }
    Integer roleId =
        db.queryForObject("SELECT role_id FROM roles WHERE role_name=?", Integer.class, role);
    LocalDateTime now = time.utcNow();
    db.update(
        "INSERT INTO"
            + " users(full_name,email,phone,password_hash,role_id,status,approved_by,approved_at,must_change_password,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,'APPROVED',?,?,TRUE,?,?)",
        request.fullName().trim(),
        email,
        request.phone(),
        passwordEncoder.encode(request.password()),
        roleId,
        managerId,
        now,
        now,
        now);
    Long userId =
        db.queryForObject(
            "SELECT user_id FROM users WHERE lower(email)=lower(?)", Long.class, email);
    audit(managerId, "CREATE_STAFF_ACCOUNT: " + userId + " " + role);
    // Gửi email và SMS chứa thông tin tài khoản và mật khẩu tạm thời
    emailService.sendStaffAccountCreated(
        email, request.fullName().trim(), role, request.password(), request.phone());
    smsService.sendStaffAccountCreated(request.phone(), email, request.password());
    // Tạo thông báo trong hệ thống cho người dùng
    notifyUser(
        userId,
        "Tài khoản của bạn đã được khởi tạo bởi Ban Quản lý. Mật khẩu tạm thời: "
            + request.password()
            + ". Vui lòng đổi mật khẩu ở lần đăng nhập đầu tiên.");
    return Map.of(
        "id",
        userId,
        "email",
        email,
        "role_name",
        role,
        "status",
        "APPROVED",
        "must_change_password",
        true);
  }

  @Transactional(readOnly = true)
  public PendingUserPageResponse pendingUsers(
      String managerEmail, String role, int page, int limit) {
    requireManager(managerEmail);
    if (page < 1 || limit < 1 || limit > 100) {
      throw new ResponseStatusException(BAD_REQUEST, "page phải từ 1 và limit phải từ 1 đến 100");
    }
    String normalizedRole = normalizeStaffRole(role);
    String roleClause = normalizedRole == null ? "" : " AND r.role_name=?";
    String countSql =
        "SELECT count(*) FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.status='PENDING'"
            + " AND u.deleted_at IS NULL AND r.role_name IN"
            + " ('HEAD_TRAINER','VETERINARIAN','HEAD_VETERINARIAN','GROOM')"
            + roleClause;
    List<Object> countArgs = normalizedRole == null ? List.of() : List.of(normalizedRole);
    long total = db.queryForObject(countSql, Long.class, countArgs.toArray());
    String listSql =
        "SELECT u.user_id,u.full_name,u.email,u.phone,r.role_name,u.status,u.created_at FROM users"
            + " u JOIN roles r ON r.role_id=u.role_id WHERE u.status='PENDING' AND u.deleted_at IS"
            + " NULL AND r.role_name IN ('HEAD_TRAINER','VETERINARIAN','HEAD_VETERINARIAN','GROOM')"
            + roleClause
            + " ORDER BY u.created_at ASC,u.user_id ASC LIMIT ? OFFSET ?";
    var args = new ArrayList<Object>(countArgs);
    args.add(limit);
    args.add((long) (page - 1) * limit);
    List<PendingUserResponse> data =
        db.queryForList(listSql, args.toArray()).stream()
            .map(ClubManagerService::toPendingUserResponse)
            .toList();
    return new PendingUserPageResponse(data, total, page, limit);
  }

  @Transactional(readOnly = true)
  public List<PendingUserResponse> users(String actorEmail, String status, String role) {
    String requestedStatus =
        StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "APPROVED";
    if (!List.of("APPROVED", "LOCKED", "REJECTED").contains(requestedStatus)) {
      throw new ResponseStatusException(BAD_REQUEST, "status không hợp lệ");
    }
    String normalizedRole = normalizeUserRoleFilter(role);
    String actorRole = requireActiveUserRole(actorEmail);
    if ("HEAD_TRAINER".equals(actorRole)) {
      if (!"APPROVED".equals(requestedStatus) || !"HORSE_OWNER".equals(normalizedRole)) {
        throw new ResponseStatusException(
            FORBIDDEN, "Chỉ Club Manager đã được duyệt mới được thao tác");
      }
    } else if (!"CLUB_MANAGER".equals(actorRole)) {
      throw new ResponseStatusException(
          FORBIDDEN, "Chỉ Club Manager đã được duyệt mới được thao tác");
    }
    String roleClause = normalizedRole == null ? "" : " AND r.role_name=?";
    var args = new ArrayList<Object>();
    args.add(requestedStatus);
    if (normalizedRole != null) args.add(normalizedRole);
    return db
        .queryForList(
            "SELECT u.user_id,u.full_name,u.email,u.phone,r.role_name,u.status,u.created_at FROM"
                + " users u JOIN roles r ON r.role_id=u.role_id WHERE u.status=? AND u.deleted_at"
                + " IS NULL"
                + roleClause
                + " ORDER BY u.full_name,u.user_id",
            args.toArray())
        .stream()
        .map(ClubManagerService::toPendingUserResponse)
        .toList();
  }

  private static String normalizeUserRoleFilter(String role) {
    if (!StringUtils.hasText(role)) return null;
    String trimmed = role.trim();
    if ("HorseOwner".equalsIgnoreCase(trimmed)) return "HORSE_OWNER";
    if ("HeadTrainer".equalsIgnoreCase(trimmed)) return "HEAD_TRAINER";
    if ("ClubManager".equalsIgnoreCase(trimmed)) return "CLUB_MANAGER";
    return trimmed.toUpperCase(Locale.ROOT);
  }

  private String requireActiveUserRole(String email) {
    var verified =
        com.example.springbootbackend.security.JwtAuthenticationFilter.currentActor(email);
    if (verified != null) return verified.role();
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id WHERE"
                + " lower(u.email)=lower(?) AND u.status='APPROVED' AND"
                + " u.must_change_password=FALSE AND u.deleted_at IS NULL",
            email);
    if (rows.isEmpty())
      throw new ResponseStatusException(
          FORBIDDEN, "Chỉ Club Manager đã được duyệt mới được thao tác");
    return (String) rows.getFirst().get("role_name");
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
    return db.queryForList(
        "SELECT u.user_id AS id,u.full_name,u.email FROM users u JOIN roles r ON"
            + " r.role_id=u.role_id WHERE r.role_name='GROOM' AND u.status='APPROVED' AND"
            + " u.must_change_password=FALSE AND u.deleted_at IS NULL ORDER BY"
            + " u.full_name,u.user_id");
  }

  public Map<String, Object> incidentAssignees(String managerEmail) {
    requireManager(managerEmail);
    var rows =
        db.queryForList(
            "SELECT u.user_id AS id,u.full_name,u.email,r.role_name FROM users u JOIN roles r ON"
                + " r.role_id=u.role_id WHERE r.role_name IN"
                + " ('HEAD_TRAINER','VETERINARIAN','HEAD_VETERINARIAN','GROOM') AND"
                + " u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL"
                + " ORDER BY r.role_name,u.full_name,u.user_id");
    return Map.of("data", rows);
  }

  @Transactional
  public UserApprovalResponse approve(String managerEmail, Long userId) {
    long managerId = requireManager(managerEmail);
    Map<String, Object> user = findUser(userId);
    ensurePendingStaff(user);
    LocalDateTime now = time.utcNow();
    db.update(
        "UPDATE users SET status='APPROVED', approved_by=?, approved_at=?, updated_at=? WHERE"
            + " user_id=? AND status='PENDING'",
        managerId,
        now,
        now,
        userId);
    notifyAccount(userId, ACCOUNT_APPROVED, Map.of());
    audit(managerId, "APPROVE_USER: " + userId);
    return new UserApprovalResponse(userId, "APPROVED", managerId, now);
  }

  @Transactional
  public Map<String, Object> reject(String managerEmail, Long userId, String reason) {
    long managerId = requireManager(managerEmail);
    Map<String, Object> user = findUser(userId);
    ensurePendingStaff(user);
    LocalDateTime now = time.utcNow();
    db.update(
        "UPDATE users SET status='REJECTED', approved_by=?, approved_at=?, updated_at=? WHERE"
            + " user_id=? AND status='PENDING'",
        managerId,
        now,
        now,
        userId);
    revokeActiveRefreshTokens(userId);
    String message =
        StringUtils.hasText(reason)
            ? "Tài khoản bị từ chối. Lý do: " + reason.trim()
            : "Tài khoản của bạn đã bị từ chối bởi Club Manager.";
    notifyAccount(
        userId,
        ACCOUNT_REJECTED,
        StringUtils.hasText(reason) ? Map.of("reason", reason.trim()) : Map.of());
    audit(
        managerId,
        "REJECT_USER: " + userId + (StringUtils.hasText(reason) ? " - " + reason.trim() : ""));
    return Map.of("id", userId, "status", "REJECTED");
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> roleChangeRequests(String managerEmail, String status) {
    requireManager(managerEmail);
    String requestedStatus = StringUtils.hasText(status) ? status.trim() : "Pending";
    if (!List.of("Pending", "Approved", "Rejected").contains(requestedStatus)) {
      throw new ResponseStatusException(BAD_REQUEST, "status không hợp lệ");
    }
    return db.queryForList(
        "SELECT u.role_change_request_id AS id,u.user_id,u.full_name,u.email,cr.role_name AS"
            + " current_role,rr.role_name AS requested_role,u.role_change_reason AS"
            + " reason,u.role_change_status AS status,u.role_change_created_at AS"
            + " created_at,u.role_change_reviewed_by AS reviewed_by,u.role_change_reviewed_at AS"
            + " reviewed_at FROM users u LEFT JOIN roles cr ON cr.role_id=u.role_id JOIN roles rr"
            + " ON rr.role_id=u.requested_role_id WHERE u.role_change_status=? ORDER BY"
            + " u.role_change_created_at ASC",
        requestedStatus);
  }

  @Transactional
  public Map<String, Object> handleRoleChange(
      String managerEmail, String requestId, String action) {
    long managerId = requireManager(managerEmail);
    String normalizedAction = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
    if (!normalizedAction.equals("approve") && !normalizedAction.equals("reject")) {
      throw new ResponseStatusException(BAD_REQUEST, "action phải là approve hoặc reject");
    }
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT role_change_request_id AS id,user_id,requested_role_id,role_change_status AS"
                + " status FROM users WHERE role_change_request_id=? FOR UPDATE",
            requestId);
    if (rows.isEmpty())
      throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu đổi vai trò");
    Map<String, Object> request = rows.getFirst();
    if (!"Pending".equals(request.get("status"))) {
      throw new ResponseStatusException(CONFLICT, "Yêu cầu đổi vai trò đã được xử lý");
    }
    LocalDateTime now = time.utcNow();
    Long userId = ((Number) request.get("user_id")).longValue();
    if (normalizedAction.equals("approve")) {
      String currentRole =
          db.queryForObject(
              "SELECT r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id WHERE"
                  + " u.user_id=?",
              String.class,
              userId);
      String requestedRole =
          db.queryForObject(
              "SELECT role_name FROM roles WHERE role_id=?",
              String.class,
              request.get("requested_role_id"));
      if ("GROOM".equals(currentRole) && !"GROOM".equals(requestedRole))
        assertNoUpcomingGroomAssignments(userId);
      db.update(
          "UPDATE users SET role_id=?, role_change_status='Approved', role_change_reviewed_by=?,"
              + " role_change_reviewed_at=?, updated_at=? WHERE user_id=?",
          request.get("requested_role_id"),
          managerId,
          now,
          now,
          userId);
      revokeActiveRefreshTokens(userId);
      notifyAccount(
          userId, ROLE_CHANGED, Map.of("old_role", currentRole, "new_role", requestedRole));
    } else {
      db.update(
          "UPDATE users SET role_change_status='Rejected', role_change_reviewed_by=?,"
              + " role_change_reviewed_at=?, updated_at=? WHERE user_id=?",
          managerId,
          now,
          now,
          userId);
      notifyAccount(userId, ROLE_REJECTED, Map.of());
    }
    audit(
        managerId,
        (normalizedAction.equals("approve") ? "APPROVE_ROLE_CHANGE: " : "REJECT_ROLE_CHANGE: ")
            + requestId);
    return Map.of(
        "id",
        requestId,
        "status",
        normalizedAction.equals("approve") ? "Approved" : "Rejected",
        "reviewed_by",
        managerId,
        "reviewed_at",
        now);
  }

  @Transactional
  public Map<String, Object> lock(String managerEmail, Long userId, String reason) {
    long managerId = requireManager(managerEmail);
    Map<String, Object> user = findUser(userId);
    db.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE", userId);
    user = findUser(userId);
    String targetRole = (String) user.get("role_name");
    if (managerId == userId || "CLUB_MANAGER".equals(targetRole)) {
      throw new ResponseStatusException(
          FORBIDDEN, "Không thể khóa Club Manager hoặc chính tài khoản của bạn");
    }
    if (!"APPROVED".equals(user.get("status"))) {
      throw new ResponseStatusException(CONFLICT, "Chỉ được khóa tài khoản đã APPROVED");
    }
    if ("GROOM".equals(targetRole)) assertNoUpcomingGroomAssignments(userId);
    LocalDateTime now = time.utcNow();
    db.update(
        "UPDATE users SET status='LOCKED', updated_at=? WHERE user_id=? AND status='APPROVED'",
        now,
        userId);
    revokeActiveRefreshTokens(userId);
    com.example.springbootbackend.security.JwtAuthenticationFilter.invalidateAll();
    String message =
        StringUtils.hasText(reason)
            ? "Tài khoản đã bị khóa. Lý do: " + reason.trim()
            : "Tài khoản của bạn đã bị khóa.";
    notifyAccount(
        userId,
        ACCOUNT_LOCKED,
        StringUtils.hasText(reason) ? Map.of("reason", reason.trim()) : Map.of());
    audit(
        managerId,
        "LOCK_USER: " + userId + (StringUtils.hasText(reason) ? " - " + reason.trim() : ""));
    return Map.of("id", userId, "status", "LOCKED");
  }

  @Transactional
  public Map<String, Object> unlock(String managerEmail, Long userId) {
    long managerId = requireManager(managerEmail);
    db.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE", userId);
    Map<String, Object> user = findUser(userId);
    String targetRole = (String) user.get("role_name");
    if (managerId == userId || "CLUB_MANAGER".equals(targetRole)) {
      throw new ResponseStatusException(
          FORBIDDEN, "Không thể mở khóa Club Manager hoặc chính tài khoản của bạn");
    }
    if (!"LOCKED".equals(user.get("status"))) {
      throw new ResponseStatusException(CONFLICT, "Chỉ mở khóa được tài khoản đang LOCKED");
    }
    LocalDateTime now = time.utcNow();
    db.update(
        "UPDATE users SET status='APPROVED', updated_at=? WHERE user_id=? AND status='LOCKED'",
        now,
        userId);
    notifyAccount(userId, ACCOUNT_UNLOCKED, Map.of());
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
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT u.user_id,u.status,r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id"
                + " WHERE u.user_id=? AND u.deleted_at IS NULL FOR UPDATE",
            userId);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy tài khoản");
    Map<String, Object> target = rows.getFirst();
    String currentRole = (String) target.get("role_name");
    if (managerId == userId || "CLUB_MANAGER".equals(currentRole)) {
      throw new ResponseStatusException(
          FORBIDDEN, "Không thể thay đổi vai trò của Club Manager hoặc chính tài khoản của bạn");
    }
    if (!"APPROVED".equals(target.get("status"))) {
      throw new ResponseStatusException(CONFLICT, "Chỉ được đổi vai trò tài khoản đang APPROVED");
    }
    if (normalizedRole.equals(currentRole)) {
      return Map.of("id", userId, "previous_role", currentRole, "role_name", currentRole);
    }
    if ("GROOM".equals(currentRole) && !"GROOM".equals(normalizedRole))
      assertNoUpcomingGroomAssignments(userId);
    Integer targetRoleId =
        db.queryForObject(
            "SELECT role_id FROM roles WHERE role_name=?", Integer.class, normalizedRole);
    LocalDateTime now = time.utcNow();
    db.update(
        "UPDATE users SET role_id=?,updated_at=? WHERE user_id=? AND status='APPROVED'",
        targetRoleId,
        now,
        userId);
    revokeActiveRefreshTokens(userId);
    com.example.springbootbackend.security.JwtAuthenticationFilter.invalidateAll();
    notifyAccount(
        userId, ROLE_CHANGED, Map.of("old_role", currentRole, "new_role", normalizedRole));
    audit(managerId, "UPDATE_USER_ROLE: " + userId + " " + currentRole + " -> " + normalizedRole);
    return Map.of("id", userId, "previous_role", currentRole, "role_name", normalizedRole);
  }

  public Map<String, Object> operationsReport(String managerEmail, LocalDate from, LocalDate to) {
    requireManager(managerEmail);
    LocalDate start = from == null ? time.today() : from;
    LocalDate end = to == null ? start : to;
    validateReportRange(start, end);
    var health =
        db.queryForList(
            "SELECT current_status,count(*) AS total FROM horses WHERE deleted_at IS NULL GROUP BY"
                + " current_status ORDER BY current_status");
    var team =
        db.queryForList(
            "SELECT r.role_name,count(*) AS total FROM users u JOIN roles r ON r.role_id=u.role_id"
                + " WHERE u.status='APPROVED' AND u.deleted_at IS NULL AND r.role_name IN"
                + " ('HEAD_TRAINER','HEAD_VETERINARIAN','VETERINARIAN','GROOM','CLUB_MANAGER')"
                + " GROUP BY r.role_name ORDER BY r.role_name");
    Map<String, Object> counts =
        db.queryForList(
                "SELECT (SELECT count(*) FROM calendar_events ce JOIN horses h ON h.id=ce.horse_id"
                    + " WHERE h.deleted_at IS NULL AND ce.event_type IN"
                    + " ('Training','TrialRun','Rest') AND ce.status IN"
                    + " ('Scheduled','InProgress','Blocked') AND ce.event_date>=? AND"
                    + " ce.event_date<=?) AS scheduled_sessions,(SELECT count(*) FROM"
                    + " daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id JOIN"
                    + " horses h ON h.id=t.horse_id WHERE h.deleted_at IS NULL AND ce.event_date>=?"
                    + " AND ce.event_date<=?) AS total_tasks,(SELECT count(*) FROM daily_task_logs"
                    + " t JOIN calendar_events ce ON ce.id=t.calendar_event_id JOIN horses h ON"
                    + " h.id=t.horse_id WHERE h.deleted_at IS NULL AND ce.event_date>=? AND"
                    + " ce.event_date<=? AND t.status='Completed') AS completed_tasks,(SELECT"
                    + " count(*) FROM stable_incidents i JOIN horses h ON h.id=i.horse_id WHERE"
                    + " h.deleted_at IS NULL AND i.status IN"
                    + " ('Pending','InProgress','AwaitingClosure')) AS pending_incidents",
                start,
                end,
                start,
                end,
                start,
                end)
            .getFirst();
    Long scheduledSessions = ((Number) counts.get("scheduled_sessions")).longValue();
    Long totalTasks = ((Number) counts.get("total_tasks")).longValue();
    Long completedTasks = ((Number) counts.get("completed_tasks")).longValue();
    Long pendingIncidents = ((Number) counts.get("pending_incidents")).longValue();
    Map<String, Object> training =
        db.queryForList(
                "SELECT count(*) FILTER (WHERE ts.status='Scheduled') AS scheduled,count(*) FILTER"
                    + " (WHERE ts.status='Completed') AS completed,count(*) FILTER (WHERE"
                    + " ts.status='InProgress') AS in_progress,count(*) FILTER (WHERE"
                    + " ts.status='Cancelled') AS cancelled,count(*) FILTER (WHERE"
                    + " ts.status='Blocked') AS blocked FROM training_schedules ts JOIN"
                    + " calendar_events ce ON ce.id=ts.calendar_event_id JOIN horses h ON"
                    + " h.id=ts.horse_id WHERE h.deleted_at IS NULL AND ce.event_date>=? AND"
                    + " ce.event_date<=?",
                start,
                end)
            .getFirst();
    Map<String, Object> finances =
        db.queryForList(
                "SELECT COALESCE(SUM(fr.amount) FILTER (WHERE lower(fr.transaction_type)='care'),0)"
                    + " AS care_cost,COALESCE(SUM(fr.amount) FILTER (WHERE"
                    + " lower(fr.transaction_type)='medical'),0) AS"
                    + " medical_cost,COALESCE(SUM(fr.amount) FILTER (WHERE"
                    + " lower(fr.transaction_type)='prize'),0) AS"
                    + " prize_income,COALESCE(SUM(fr.amount) FILTER (WHERE"
                    + " lower(fr.transaction_type)='other'),0) AS other_amount FROM"
                    + " financial_reports fr JOIN horses h ON h.id=fr.horse_id WHERE h.deleted_at"
                    + " IS NULL AND fr.billing_period>=? AND fr.billing_period<=?",
                YearMonth.from(start).toString(),
                YearMonth.from(end).toString())
            .getFirst();
    Map<String, Object> competition =
        db.queryForList(
                "SELECT count(*) FILTER (WHERE e.status='Registered') AS registered,count(*) FILTER"
                    + " (WHERE e.status='Blocked') AS blocked,count(*) FILTER (WHERE"
                    + " e.status='Completed') AS completed,count(*) FILTER (WHERE"
                    + " e.status='Cancelled') AS cancelled,count(*) FILTER (WHERE"
                    + " e.status='Completed' AND e.result_position=1) AS wins,count(*) FILTER"
                    + " (WHERE e.status='Completed' AND e.result_position BETWEEN 1 AND 3) AS"
                    + " podiums,COALESCE(SUM(e.prize_amount) FILTER (WHERE e.status='Completed'),0)"
                    + " AS total_prize FROM horse_race_entries e JOIN races r ON r.id=e.race_id"
                    + " JOIN horses h ON h.id=e.horse_id WHERE h.deleted_at IS NULL AND"
                    + " r.race_date>=? AND r.race_date<=? AND r.is_simulated=FALSE",
                start,
                end)
            .getFirst();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("from", start);
    result.put("to", end);
    result.put("horses_by_health_status", health);
    result.put("approved_staff_by_role", team);
    result.put("scheduled_training_sessions", scheduledSessions);
    result.put("groom_tasks", Map.of("total", totalTasks, "completed", completedTasks));
    result.put("pending_incidents", pendingIncidents);
    result.put("training_performance", training);
    result.put("operating_costs_and_prize_income", finances);
    result.put("official_competition", competition);
    return result;
  }

  public Map<String, Object> competitionReport(String managerEmail, LocalDate from, LocalDate to) {
    requireManager(managerEmail);
    LocalDate start = from == null ? time.today().minusYears(1) : from;
    LocalDate end = to == null ? time.today().plusDays(60) : to;
    validateReportRange(start, end);
    Map<String, Object> summary =
        db.queryForList(
                "SELECT count(DISTINCT r.id) FILTER (WHERE"
                    + " COALESCE(r.status,'Scheduled')<>'Cancelled') AS total_races,count(*) FILTER"
                    + " (WHERE e.status='Registered') AS registered,count(*) FILTER (WHERE"
                    + " e.status='Blocked') AS blocked,count(*) FILTER (WHERE e.status='Completed')"
                    + " AS completed,count(*) FILTER (WHERE e.status='Cancelled') AS"
                    + " cancelled,count(*) FILTER (WHERE e.status='Completed' AND"
                    + " e.result_position=1) AS wins,count(*) FILTER (WHERE e.status='Completed'"
                    + " AND e.result_position BETWEEN 1 AND 3) AS"
                    + " podiums,COALESCE(SUM(e.prize_amount) FILTER (WHERE e.status='Completed'),0)"
                    + " AS total_prize FROM races r LEFT JOIN horse_race_entries e ON"
                    + " e.race_id=r.id LEFT JOIN horses h ON h.id=e.horse_id AND h.deleted_at IS"
                    + " NULL WHERE r.is_simulated=FALSE AND NOT EXISTS (SELECT 1 FROM"
                    + " race_simulations sim WHERE sim.race_id=r.id) AND r.race_date>=? AND"
                    + " r.race_date<=? AND (e.id IS NULL OR h.id IS NOT NULL)",
                start,
                end)
            .getFirst();
    var leaderboard =
        db.query(
            "SELECT h.id AS horse_id,h.horse_name,count(*) FILTER (WHERE e.status='Completed') AS"
                + " completed_races,count(*) FILTER (WHERE e.status='Completed' AND"
                + " e.result_position=1) AS wins,count(*) FILTER (WHERE e.status='Completed' AND"
                + " e.result_position BETWEEN 1 AND 3) AS podiums,MIN(e.result_position) FILTER"
                + " (WHERE e.status='Completed') AS best_position,COALESCE(SUM(e.prize_amount)"
                + " FILTER (WHERE e.status='Completed'),0) AS total_prize FROM horse_race_entries e"
                + " JOIN races r ON r.id=e.race_id JOIN horses h ON h.id=e.horse_id WHERE"
                + " h.deleted_at IS NULL AND r.is_simulated=FALSE AND r.race_date>=? AND"
                + " r.race_date<=? AND e.status='Completed' GROUP BY h.id,h.horse_name ORDER BY"
                + " wins DESC,podiums DESC,total_prize DESC,best_position ASC,h.horse_name ASC",
            VetRows.MAPPER,
            start,
            end);
    var recentResults =
        db.query(
            "SELECT e.id AS"
                + " entry_id,e.horse_id,h.horse_name,e.race_id,r.race_name,r.grade,r.race_date,r.location,e.result_position,e.prize_amount"
                + " FROM horse_race_entries e JOIN races r ON r.id=e.race_id JOIN horses h ON"
                + " h.id=e.horse_id WHERE h.deleted_at IS NULL AND r.is_simulated=FALSE AND"
                + " e.status='Completed' AND r.race_date>=? AND r.race_date<=? ORDER BY r.race_date"
                + " DESC,e.result_position ASC,h.horse_name ASC LIMIT 20",
            VetRows.MAPPER,
            start,
            end);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("from", start);
    result.put("to", end);
    result.put("summary", summary);
    result.put("leaderboard", leaderboard);
    result.put("recent_results", recentResults);
    return result;
  }

  public Map<String, Object> officialRaces(String managerEmail) {
    requireManager(managerEmail);
    var rows =
        db.query(
            "SELECT"
                + " r.id,r.race_name,r.grade,r.distance_category,r.distance_meters,r.race_date,r.location,r.description,COALESCE(r.status,'Scheduled')"
                + " AS status,r.cancel_reason,count(e.id) FILTER (WHERE h.id IS NOT NULL AND"
                + " e.status='Registered') AS registered_count,count(e.id) FILTER (WHERE h.id IS"
                + " NOT NULL AND e.status='Blocked') AS blocked_count,count(e.id) FILTER (WHERE"
                + " h.id IS NOT NULL AND e.status='Completed') AS completed_count,count(e.id)"
                + " FILTER (WHERE h.id IS NOT NULL AND e.status='Cancelled') AS"
                + " cancelled_count,COALESCE(SUM(e.prize_amount) FILTER (WHERE h.id IS NOT NULL AND"
                + " e.status='Completed'),0) AS total_prize_amount FROM races r LEFT JOIN"
                + " horse_race_entries e ON e.race_id=r.id LEFT JOIN horses h ON h.id=e.horse_id"
                + " AND h.deleted_at IS NULL WHERE r.is_simulated=FALSE AND NOT EXISTS (SELECT 1"
                + " FROM race_simulations sim WHERE sim.race_id=r.id) GROUP BY"
                + " r.id,r.race_name,r.grade,r.distance_category,r.distance_meters,r.race_date,r.location,r.description,r.status,r.cancel_reason"
                + " ORDER BY CASE WHEN COALESCE(r.status,'Scheduled')='Cancelled' THEN 2 WHEN"
                + " r.race_date<? THEN 1 ELSE 0 END, r.race_date, r.race_name, r.id",
            VetRows.MAPPER,
            time.today());
    return Map.of("data", rows);
  }

  @Transactional
  public Map<String, Object> createOfficialRace(String managerEmail, String ip, JsonNode body) {
    long managerId = requireManager(managerEmail);
    var fields =
        VetInput.parse(
            body,
            "race_name:150",
            "grade:10",
            "distance_category:20",
            "distance_meters:number",
            "race_date:date",
            "location:150",
            "description:0");
    VetInput.required(fields, "race_name", "race_date");
    String raceName = fields.get("race_name").toString().trim();
    if (raceName.isEmpty())
      throw VetException.invalid("race_name", "Tên giải đấu không được để trống");
    LocalDate raceDate = (LocalDate) fields.get("race_date");
    if (raceDate.isBefore(time.today()))
      throw VetException.invalid(
          "race_date", "Giải đấu chính thức phải có ngày thi đấu từ hôm nay trở đi");
    Integer distance = parseOptionalDistance(fields.get("distance_meters"));
    assertNoDuplicateOfficialRace(raceName, raceDate, null);
    String id = UUID.randomUUID().toString();
    Race race = new Race();
    race.setId(id);
    race.setRaceName(raceName);
    race.setGrade(blankToNull(fields.get("grade")));
    race.setDistanceCategory(blankToNull(fields.get("distance_category")));
    race.setDistanceMeters(distance);
    race.setRaceDate(raceDate);
    race.setLocation(blankToNull(fields.get("location")));
    race.setDescription(blankToNull(fields.get("description")));
    race.setSimulated(false);
    race.setStatus("Scheduled");
    raceRepository.saveAndFlush(race);
    audit(managerId, "CREATE_OFFICIAL_RACE:" + id, ip);
    notifyActiveUsersByRole(
        "HEAD_TRAINER",
        "Races",
        id,
        "Club Manager vừa tạo giải đấu chính thức mới: \""
            + raceName
            + "\" ngày "
            + raceDate
            + ".");
    return officialRaceSummaryRow(id);
  }

  @Transactional
  public Map<String, Object> updateOfficialRace(
      String managerEmail, String ip, UUID raceId, JsonNode body) {
    long managerId = requireManager(managerEmail);
    String id = raceId.toString();
    var existingRows =
        db.query(
            "SELECT"
                + " r.id,r.race_name,r.grade,r.distance_category,r.distance_meters,r.race_date,r.location,r.description,COALESCE(r.status,'Scheduled')"
                + " AS status FROM races r WHERE r.id=? AND r.is_simulated=FALSE AND NOT EXISTS"
                + " (SELECT 1 FROM race_simulations sim WHERE sim.race_id=r.id) FOR UPDATE OF r",
            VetRows.MAPPER,
            id);
    if (existingRows.isEmpty())
      throw new VetException(404, "RACE_NOT_FOUND", "Không tìm thấy giải đấu chính thức");
    var existing = existingRows.getFirst();
    if ("Cancelled".equals(existing.get("status")))
      throw new VetException(409, "RACE_CANCELLED", "Không thể chỉnh sửa giải đấu đã hủy");
    var fields =
        VetInput.parse(
            body,
            "race_name:150",
            "grade:10",
            "distance_category:20",
            "distance_meters:number",
            "race_date:date",
            "location:150",
            "description:0");
    String newName =
        fields.containsKey("race_name")
            ? Objects.toString(fields.get("race_name"), "").trim()
            : existing.get("race_name").toString();
    if (newName.isEmpty())
      throw VetException.invalid("race_name", "Tên giải đấu không được để trống");
    LocalDate oldDate = (LocalDate) existing.get("race_date");
    LocalDate newDate =
        fields.containsKey("race_date") ? (LocalDate) fields.get("race_date") : oldDate;
    if (newDate == null) throw VetException.invalid("race_date", "Ngày thi đấu bắt buộc");
    Integer oldDistance = existing.get("distance_meters") instanceof Number n ? n.intValue() : null;
    Integer newDistance =
        fields.containsKey("distance_meters")
            ? parseOptionalDistance(fields.get("distance_meters"))
            : oldDistance;
    String newGrade =
        fields.containsKey("grade")
            ? blankToNull(fields.get("grade"))
            : (String) existing.get("grade");
    String newDistanceCategory =
        fields.containsKey("distance_category")
            ? blankToNull(fields.get("distance_category"))
            : (String) existing.get("distance_category");
    String newLocation =
        fields.containsKey("location")
            ? blankToNull(fields.get("location"))
            : (String) existing.get("location");
    String newDescription =
        fields.containsKey("description")
            ? blankToNull(fields.get("description"))
            : (String) existing.get("description");
    boolean nameChanged = !newName.equals(existing.get("race_name").toString());
    boolean dateChanged = !newDate.equals(oldDate);
    boolean distanceChanged = !Objects.equals(newDistance, oldDistance);
    Long completedCount =
        db.queryForObject(
            "SELECT count(*) FROM horse_race_entries e JOIN horses h ON h.id=e.horse_id "
                + "WHERE e.race_id=? AND e.status='Completed' AND h.deleted_at IS NULL",
            Long.class,
            id);
    if (completedCount != null
        && completedCount > 0
        && (nameChanged || dateChanged || distanceChanged)) {
      throw new VetException(
          409,
          "RACE_HAS_COMPLETED_RESULTS",
          "Giải đấu đã có kết quả hoàn thành nên không thể thay đổi tên giải, ngày thi đấu hoặc cự"
              + " ly");
    }
    assertNoDuplicateOfficialRace(newName, newDate, id);
    List<Map<String, Object>> activeEntries =
        db.query(
            "SELECT e.id,e.horse_id,e.calendar_event_id,h.horse_name,h.owner_id FROM"
                + " horse_race_entries e JOIN horses h ON h.id=e.horse_id WHERE e.race_id=? AND"
                + " h.deleted_at IS NULL AND e.status IN ('Registered','Blocked') ORDER BY"
                + " e.horse_id,e.id",
            VetRows.MAPPER,
            id);
    if (dateChanged) {
      if (newDate.isBefore(time.today()))
        throw VetException.invalid("race_date", "Ngày thi đấu mới phải từ hôm nay trở đi");
      Set<String> lockedHorses = new LinkedHashSet<>();
      for (var entry : activeEntries) lockedHorses.add(entry.get("horse_id").toString());
      for (String horseId : lockedHorses) {
        db.queryForList(
            "SELECT id FROM horses WHERE id=? AND deleted_at IS NULL FOR UPDATE", horseId);
      }
      List<Map<String, Object>> allConflicts = new ArrayList<>();
      for (var entry : activeEntries) {
        var horseConflicts =
            conflicts.checkCalendarConflicts(
                entry.get("horse_id").toString(),
                newDate,
                null,
                null,
                entry.get("calendar_event_id").toString(),
                null,
                null);
        for (var conflict : horseConflicts) {
          Map<String, Object> item = new LinkedHashMap<>(conflict);
          item.put("horse_id", entry.get("horse_id"));
          allConflicts.add(item);
        }
      }
      if (!allConflicts.isEmpty()) {
        throw new VetException(
            409,
            "SCHEDULE_CONFLICT",
            "Không thể dời giải sang ngày " + newDate + " vì có ngựa đã đăng ký bị trùng lịch",
            Map.of("conflicts", allConflicts));
      }
      for (var entry : activeEntries) {
        db.update(
            "UPDATE calendar_events SET event_date=?,title=? WHERE id=?",
            newDate,
            newName,
            entry.get("calendar_event_id"));
      }
    } else if (nameChanged) {
      db.update(
          "UPDATE calendar_events ce SET title=? FROM horse_race_entries e "
              + "WHERE ce.id=e.calendar_event_id AND e.race_id=? AND e.status<>'Cancelled'",
          newName,
          id);
    }
    db.update(
        "UPDATE races SET"
            + " race_name=?,grade=?,distance_category=?,distance_meters=?,race_date=?,location=?,description=?"
            + " WHERE id=?",
        newName,
        newGrade,
        newDistanceCategory,
        newDistance,
        newDate,
        newLocation,
        newDescription,
        id);
    audit(managerId, "UPDATE_OFFICIAL_RACE:" + id, ip);
    if (dateChanged && !activeEntries.isEmpty()) {
      String msg =
          "Giải đấu \""
              + newName
              + "\" đã được Club Manager dời từ ngày "
              + oldDate
              + " sang ngày "
              + newDate
              + ".";
      notifyActiveUsersByRole("HEAD_TRAINER", "Races", id, msg);
      Set<Long> ownerIds = new LinkedHashSet<>();
      for (var entry : activeEntries) {
        if (entry.get("owner_id") instanceof Number ownerId) ownerIds.add(ownerId.longValue());
      }
      for (long ownerId : ownerIds) notifyActiveOwner(ownerId, "Races", id, msg);
    }
    return officialRaceSummaryRow(id);
  }

  @Transactional
  public Map<String, Object> cancelOfficialRace(
      String managerEmail, String ip, UUID raceId, JsonNode body) {
    long managerId = requireManager(managerEmail);
    var fields = VetInput.parse(body, "reason:255");
    VetInput.required(fields, "reason");
    String reason = fields.get("reason").toString();
    String id = raceId.toString();
    var existingRows =
        db.query(
            "SELECT r.id,r.race_name,r.race_date,COALESCE(r.status,'Scheduled') AS status FROM"
                + " races r WHERE r.id=? AND r.is_simulated=FALSE AND NOT EXISTS (SELECT 1 FROM"
                + " race_simulations sim WHERE sim.race_id=r.id) FOR UPDATE OF r",
            VetRows.MAPPER,
            id);
    if (existingRows.isEmpty())
      throw new VetException(404, "RACE_NOT_FOUND", "Không tìm thấy giải đấu chính thức");
    var race = existingRows.getFirst();
    if ("Cancelled".equals(race.get("status")))
      throw new VetException(409, "ALREADY_CANCELLED", "Giải đấu đã được hủy trước đó");
    Long completedCount =
        db.queryForObject(
            "SELECT count(*) FROM horse_race_entries e JOIN horses h ON h.id=e.horse_id "
                + "WHERE e.race_id=? AND e.status='Completed' AND h.deleted_at IS NULL",
            Long.class,
            id);
    if (completedCount != null && completedCount > 0)
      throw new VetException(
          409, "RACE_HAS_COMPLETED_RESULTS", "Không thể hủy giải đấu đã có kết quả hoàn thành");
    var activeEntries =
        db.query(
            "SELECT e.id,e.horse_id,e.calendar_event_id,h.owner_id FROM horse_race_entries e JOIN"
                + " horses h ON h.id=e.horse_id WHERE e.race_id=? AND h.deleted_at IS NULL AND"
                + " e.status IN ('Registered','Blocked') ORDER BY e.horse_id,e.id",
            VetRows.MAPPER,
            id);
    Set<String> lockedHorses = new LinkedHashSet<>();
    for (var entry : activeEntries) lockedHorses.add(entry.get("horse_id").toString());
    for (String horseId : lockedHorses) {
      db.queryForList(
          "SELECT id FROM horses WHERE id=? AND deleted_at IS NULL FOR UPDATE", horseId);
    }
    db.update("UPDATE races SET status='Cancelled',cancel_reason=? WHERE id=?", reason, id);
    db.update(
        "UPDATE horse_race_entries SET status='Cancelled',cancel_reason=? WHERE race_id=? AND"
            + " status IN ('Registered','Blocked')",
        reason,
        id);
    db.update(
        "UPDATE calendar_events ce SET status='Cancelled' FROM horse_race_entries e "
            + "WHERE ce.id=e.calendar_event_id AND e.race_id=? AND e.status='Cancelled'",
        id);
    audit(managerId, "CANCEL_OFFICIAL_RACE:" + id, ip);
    String msg =
        "Giải đấu \""
            + race.get("race_name")
            + "\" ngày "
            + race.get("race_date")
            + " đã bị hủy bởi Club Manager. Lý do: "
            + reason
            + ".";
    notifyActiveUsersByRole("HEAD_TRAINER", "Races", id, msg);
    Set<Long> ownerIds = new LinkedHashSet<>();
    for (var entry : activeEntries) {
      if (entry.get("owner_id") instanceof Number ownerId) ownerIds.add(ownerId.longValue());
    }
    for (long ownerId : ownerIds) notifyActiveOwner(ownerId, "Races", id, msg);
    return officialRaceSummaryRow(id);
  }

  public Map<String, Object> officialRaceEntries(String managerEmail, UUID raceId) {
    requireManager(managerEmail);
    String id = raceId.toString();
    var raceRows =
        db.query(
            "SELECT"
                + " r.id,r.race_name,r.grade,r.distance_category,r.distance_meters,r.race_date,r.location,r.description,COALESCE(r.status,'Scheduled')"
                + " AS status,r.cancel_reason FROM races r WHERE r.id=? AND r.is_simulated=FALSE"
                + " AND NOT EXISTS (SELECT 1 FROM race_simulations sim WHERE sim.race_id=r.id)",
            VetRows.MAPPER,
            id);
    if (raceRows.isEmpty())
      throw new VetException(404, "RACE_NOT_FOUND", "Không tìm thấy giải đấu chính thức");
    var entries =
        db.query(
            "SELECT e.id AS"
                + " entry_id,e.horse_id,h.horse_name,h.current_status,h.readiness_status,h.is_training_locked,o.full_name"
                + " AS owner_name,reg.full_name AS"
                + " registered_by_name,e.status,e.cancel_reason,e.result_position,e.prize_amount,e.created_at"
                + " FROM horse_race_entries e JOIN horses h ON h.id=e.horse_id LEFT JOIN users o ON"
                + " o.user_id=h.owner_id LEFT JOIN users reg ON reg.user_id=e.registered_by WHERE"
                + " e.race_id=? AND h.deleted_at IS NULL ORDER BY CASE e.status WHEN 'Registered'"
                + " THEN 0 WHEN 'Blocked' THEN 1 WHEN 'Completed' THEN 2 ELSE 3"
                + " END,COALESCE(e.result_position, 9999), h.horse_name, e.id",
            VetRows.MAPPER,
            id);
    return Map.of("race", raceRows.getFirst(), "data", entries);
  }

  private Integer parseOptionalDistance(Object value) {
    if (value == null) return null;
    BigDecimal number = (BigDecimal) value;
    int distance;
    try {
      distance = number.intValueExact();
    } catch (ArithmeticException ex) {
      throw VetException.invalid("distance_meters", "Cự ly phải là số nguyên mét");
    }
    if (distance < 1 || distance > 100000)
      throw VetException.invalid("distance_meters", "Cự ly phải từ 1 đến 100.000 mét");
    return distance;
  }

  private void assertNoDuplicateOfficialRace(
      String raceName, LocalDate raceDate, String excludeRaceId) {
    String sql =
        "SELECT count(*) FROM races r WHERE lower(trim(r.race_name))=lower(?) AND r.race_date=? "
            + "AND r.is_simulated=FALSE AND COALESCE(r.status,'Scheduled')<>'Cancelled'";
    List<Object> args = new ArrayList<>();
    args.add(raceName);
    args.add(raceDate);
    if (excludeRaceId != null) {
      sql += " AND r.id<>?";
      args.add(excludeRaceId);
    }
    Long count = db.queryForObject(sql, Long.class, args.toArray());
    if (count != null && count > 0)
      throw new VetException(
          409, "DUPLICATE_RACE", "Đã tồn tại giải đấu cùng tên vào ngày " + raceDate);
  }

  private Map<String, Object> officialRaceSummaryRow(String id) {
    return db.query(
            "SELECT"
                + " r.id,r.race_name,r.grade,r.distance_category,r.distance_meters,r.race_date,r.location,r.description,COALESCE(r.status,'Scheduled')"
                + " AS status,r.cancel_reason,count(e.id) FILTER (WHERE h.id IS NOT NULL AND"
                + " e.status='Registered') AS registered_count,count(e.id) FILTER (WHERE h.id IS"
                + " NOT NULL AND e.status='Blocked') AS blocked_count,count(e.id) FILTER (WHERE"
                + " h.id IS NOT NULL AND e.status='Completed') AS completed_count,count(e.id)"
                + " FILTER (WHERE h.id IS NOT NULL AND e.status='Cancelled') AS"
                + " cancelled_count,COALESCE(SUM(e.prize_amount) FILTER (WHERE h.id IS NOT NULL AND"
                + " e.status='Completed'),0) AS total_prize_amount FROM races r LEFT JOIN"
                + " horse_race_entries e ON e.race_id=r.id LEFT JOIN horses h ON h.id=e.horse_id"
                + " AND h.deleted_at IS NULL WHERE r.id=? GROUP BY"
                + " r.id,r.race_name,r.grade,r.distance_category,r.distance_meters,r.race_date,r.location,r.description,r.status,r.cancel_reason",
            VetRows.MAPPER,
            id)
        .getFirst();
  }

  private String blankToNull(Object value) {
    if (value == null) return null;
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  public Map<String, Object> auditLogs(
      String managerEmail, LocalDate from, LocalDate to, int page, int limit) {
    requireManager(managerEmail);
    validatePage(page, limit);
    if (from != null && to != null && to.isBefore(from))
      throw new ResponseStatusException(BAD_REQUEST, "to must be on or after from");
    String where = " WHERE 1=1";
    List<Object> args = new ArrayList<>();
    if (from != null) {
      where += " AND a.created_at>=?";
      args.add(time.utcStartOf(from));
    }
    if (to != null) {
      where += " AND a.created_at<?";
      args.add(time.utcStartOf(to.plusDays(1)));
    }
    Long total =
        db.queryForObject("SELECT count(*) FROM audit_logs a" + where, Long.class, args.toArray());
    var queryArgs = new ArrayList<>(args);
    queryArgs.add(limit);
    queryArgs.add((long) (page - 1) * limit);
    var rows =
        db.query(
            "SELECT a.id,a.user_id,u.full_name AS"
                + " user_name,u.email,a.action_performed,a.ip_address,a.created_at FROM audit_logs"
                + " a LEFT JOIN users u ON u.user_id=a.user_id"
                + where
                + " ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",
            VetRows.MAPPER,
            queryArgs.toArray());
    return Map.of("data", rows, "total", total, "page", page, "limit", limit);
  }

  @Transactional
  public Map<String, Object> assignGroomTask(String managerEmail, String ip, JsonNode body) {
    long managerId = requireManager(managerEmail);
    var fields =
        VetInput.parse(body, "horse_id:uuid", "groom_id:long", "task_type:30", "task_date:date");
    VetInput.required(fields, "horse_id", "groom_id", "task_type", "task_date");
    String horseId = fields.get("horse_id").toString();
    long groomId = ((Number) fields.get("groom_id")).longValue();
    String taskType = fields.get("task_type").toString();
    LocalDate taskDate = (LocalDate) fields.get("task_date");
    if (!List.of("Feeding", "Cleaning", "Bathing", "IceBath").contains(taskType))
      throw VetException.invalid("task_type", "Values: Feeding, Cleaning, Bathing, IceBath");
    if (taskDate.isBefore(time.today().minusDays(1)) || taskDate.isAfter(time.today().plusDays(60)))
      throw VetException.invalid("task_date", "Task date must be within the next 60 days");
    if (db.queryForList(
            "SELECT id FROM horses WHERE id=? AND deleted_at IS NULL FOR UPDATE", horseId)
        .isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Horse not found");
    var grooms =
        db.queryForList(
            "SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=?"
                + " AND r.role_name='GROOM' AND u.status='APPROVED' AND"
                + " u.must_change_password=FALSE AND u.deleted_at IS NULL FOR UPDATE OF u",
            groomId);
    if (grooms.isEmpty())
      throw new ResponseStatusException(BAD_REQUEST, "Groom account is not active");
    Long duplicate =
        db.queryForObject(
            "SELECT count(*) FROM daily_task_logs t JOIN calendar_events ce ON"
                + " ce.id=t.calendar_event_id WHERE t.horse_id=? AND t.groom_id=? AND t.task_type=?"
                + " AND ce.event_date=?",
            Long.class,
            horseId,
            groomId,
            taskType,
            taskDate);
    if (duplicate != null && duplicate > 0)
      throw new ResponseStatusException(
          CONFLICT, "This task is already assigned for that horse and date");
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
    notifyRelated(
        groomId, "Daily_Task_Logs", id, taskType + " task assigned for " + taskDate + ".");
    return db.query(
            "SELECT t.id,t.horse_id,h.horse_name,t.groom_id,u.full_name AS"
                + " groom_name,t.task_type,ce.event_date AS task_date,t.status FROM daily_task_logs"
                + " t JOIN calendar_events ce ON ce.id=t.calendar_event_id JOIN horses h ON"
                + " h.id=t.horse_id LEFT JOIN users u ON u.user_id=t.groom_id WHERE t.id=?",
            VetRows.MAPPER,
            id)
        .getFirst();
  }

  public Map<String, Object> groomTasks(String managerEmail, LocalDate date) {
    requireManager(managerEmail);
    LocalDate selected = date == null ? time.today() : date;
    var rows =
        db.query(
            "SELECT t.id,t.horse_id,h.horse_name,t.groom_id,u.full_name AS"
                + " groom_name,t.task_type,ce.event_date AS task_date,t.status,t.completed_at FROM"
                + " daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id JOIN"
                + " horses h ON h.id=t.horse_id LEFT JOIN users u ON u.user_id=t.groom_id WHERE"
                + " ce.event_date=? ORDER BY CASE WHEN t.status='Pending' THEN 0 ELSE 1"
                + " END,u.full_name,h.horse_name,t.id",
            VetRows.MAPPER,
            selected);
    return Map.of("date", selected, "data", rows);
  }

  @Transactional
  public Map<String, Object> updateGroomTask(
      String managerEmail, String ip, UUID taskId, JsonNode body) {
    long managerId = requireManager(managerEmail);
    String id = taskId.toString();
    var rows =
        db.query(
            "SELECT t.id,t.groom_id,t.status,ce.id AS event_id,ce.event_date "
                + "FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                + "WHERE t.id=? FOR UPDATE OF t,ce",
            VetRows.MAPPER,
            id);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Groom task not found");
    var task = rows.getFirst();
    if (!"Pending".equals(task.get("status")))
      throw new ResponseStatusException(
          CONFLICT, "Only a pending Groom task can be reassigned or cancelled");
    Long previousGroom = task.get("groom_id") instanceof Number value ? value.longValue() : null;
    var fields = VetInput.parse(body, "action:10", "groom_id:long");
    if (fields.containsKey("action")) {
      String action = fields.get("action").toString();
      if (!"Cancel".equals(action) || fields.containsKey("groom_id"))
        throw VetException.invalid("action", "Use Cancel alone, or send groom_id to reassign");
      db.update("UPDATE daily_task_logs SET status='Cancelled' WHERE id=?", id);
      db.update("UPDATE calendar_events SET status='Cancelled' WHERE id=?", task.get("event_id"));
      if (previousGroom != null)
        notifyRelated(
            previousGroom,
            "Daily_Task_Logs",
            id,
            "A care task scheduled for " + task.get("event_date") + " was cancelled.");
      audit(managerId, "CANCEL_GROOM_TASK: " + id, ip);
    } else {
      VetInput.required(fields, "groom_id");
      long groomId = ((Number) fields.get("groom_id")).longValue();
      var grooms =
          db.queryForList(
              "SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                  + "WHERE u.user_id=? AND r.role_name='GROOM' AND u.status='APPROVED' "
                  + "AND u.must_change_password=FALSE AND u.deleted_at IS NULL FOR UPDATE OF u",
              groomId);
      if (grooms.isEmpty()) throw VetException.invalid("groom_id", "Groom account is not active");
      db.update(
          "UPDATE daily_task_logs SET groom_id=?,assigned_by=? WHERE id=?", groomId, managerId, id);
      if (previousGroom != null && previousGroom != groomId)
        notifyRelated(
            previousGroom,
            "Daily_Task_Logs",
            id,
            "A care task scheduled for " + task.get("event_date") + " was reassigned.");
      notifyRelated(
          groomId,
          "Daily_Task_Logs",
          id,
          "A care task was assigned for " + task.get("event_date") + ".");
      audit(managerId, "REASSIGN_GROOM_TASK: " + id + " to " + groomId, ip);
    }
    return db.query(
            "SELECT t.id,t.horse_id,h.horse_name,t.groom_id,u.full_name AS"
                + " groom_name,t.task_type,ce.event_date AS task_date,t.status FROM daily_task_logs"
                + " t JOIN calendar_events ce ON ce.id=t.calendar_event_id JOIN horses h ON"
                + " h.id=t.horse_id LEFT JOIN users u ON u.user_id=t.groom_id WHERE t.id=?",
            VetRows.MAPPER,
            id)
        .getFirst();
  }

  public Map<String, Object> incidents(String managerEmail, String status) {
    requireManager(managerEmail);
    String selected = StringUtils.hasText(status) ? status.trim() : "Open";
    if (!List.of("Open", "Pending", "InProgress", "AwaitingClosure", "Resolved", "All")
        .contains(selected))
      throw new ResponseStatusException(
          BAD_REQUEST,
          "status must be Open, Pending, InProgress, AwaitingClosure, Resolved, or All");
    String filter =
        switch (selected) {
          case "Open" -> "WHERE i.status IN ('Pending','InProgress','AwaitingClosure')";
          case "All" -> "";
          default -> "WHERE i.status=?";
        };
    var rows =
        "Open".equals(selected) || "All".equals(selected)
            ? db.query(
                "SELECT i.id,i.horse_id,h.horse_name,i.groom_id,g.full_name AS"
                    + " groom_name,i.issue_description,i.image_url,i.is_emergency,i.status,i.assigned_to,i.assigned_role,a.full_name"
                    + " AS assignee_name,"
                    + "i.assignment_note,i.assigned_by,i.assigned_at,i.result_note,i.result_by,i.result_at,i.reviewed_by,r.full_name"
                    + " AS reviewer_name,i.created_at,i.resolved_at FROM stable_incidents i JOIN"
                    + " horses h ON h.id=i.horse_id LEFT JOIN users g ON g.user_id=i.groom_id LEFT"
                    + " JOIN users a ON a.user_id=i.assigned_to LEFT JOIN users r ON"
                    + " r.user_id=i.reviewed_by "
                    + filter
                    + " ORDER BY i.is_emergency DESC,i.created_at DESC,i.id",
                VetRows.MAPPER)
            : db.query(
                "SELECT i.id,i.horse_id,h.horse_name,i.groom_id,g.full_name AS"
                    + " groom_name,i.issue_description,i.image_url,i.is_emergency,i.status,i.assigned_to,i.assigned_role,a.full_name"
                    + " AS assignee_name,"
                    + "i.assignment_note,i.assigned_by,i.assigned_at,i.result_note,i.result_by,i.result_at,i.reviewed_by,r.full_name"
                    + " AS reviewer_name,i.created_at,i.resolved_at FROM stable_incidents i JOIN"
                    + " horses h ON h.id=i.horse_id LEFT JOIN users g ON g.user_id=i.groom_id LEFT"
                    + " JOIN users a ON a.user_id=i.assigned_to LEFT JOIN users r ON"
                    + " r.user_id=i.reviewed_by "
                    + filter
                    + " ORDER BY i.is_emergency DESC,i.created_at DESC,i.id",
                VetRows.MAPPER,
                selected);
    return Map.of("data", rows);
  }

  @Transactional
  public Map<String, Object> assignIncident(
      String managerEmail, String ip, UUID incidentId, JsonNode body) {
    long managerId = requireManager(managerEmail);
    String id = incidentId.toString();
    var fields =
        VetInput.parse(body, "assigned_role:20", "assignee_id:long", "assignment_note:1000");
    VetInput.choice(
        fields,
        "assigned_role",
        "HEAD_TRAINER",
        "HEAD_VETERINARIAN",
        "VETERINARIAN",
        "GROOM",
        "CLUB_MANAGER");
    VetInput.required(fields, "assignment_note");
    String role = fields.get("assigned_role").toString();
    long assigneeId;
    if ("CLUB_MANAGER".equals(role)) {
      if (fields.containsKey("assignee_id"))
        throw VetException.invalid("assignee_id", "Club Manager assignment is the current manager");
      assigneeId = managerId;
    } else {
      VetInput.required(fields, "assignee_id");
      assigneeId = ((Number) fields.get("assignee_id")).longValue();
      Long valid =
          db.queryForObject(
              "SELECT count(*) FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=?"
                  + " AND r.role_name=? AND u.status='APPROVED' AND u.must_change_password=FALSE"
                  + " AND u.deleted_at IS NULL",
              Long.class,
              assigneeId,
              role);
      if (valid == null || valid == 0)
        throw VetException.invalid("assignee_id", "Assignee account is not active");
    }
    var rows =
        db.query(
            "SELECT i.id,i.status,i.assigned_to,i.groom_id,h.horse_name FROM stable_incidents i "
                + "JOIN horses h ON h.id=i.horse_id WHERE i.id=? FOR UPDATE OF i",
            VetRows.MAPPER,
            id);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Incident not found");
    Map<String, Object> incident = rows.getFirst();
    if (!List.of("Pending", "InProgress").contains(incident.get("status")))
      throw new ResponseStatusException(
          CONFLICT, "Only a pending or active incident can be assigned");
    Object previous = incident.get("assigned_to");
    db.update(
        "UPDATE stable_incidents SET"
            + " status='InProgress',assigned_to=?,assigned_role=?,assignment_note=?,assigned_by=?,assigned_at=?,reviewed_by=?,result_note=NULL,result_by=NULL,result_at=NULL"
            + " WHERE id=?",
        assigneeId,
        role,
        fields.get("assignment_note"),
        managerId,
        time.utcNow(),
        managerId,
        id);
    String horse = (String) incident.get("horse_name");
    String msg =
        "Incident for "
            + horse
            + " assigned to you. Instructions: "
            + fields.get("assignment_note");
    notifyRelated(assigneeId, "Stable_Incidents", id, msg);
    if (previous instanceof Number old && old.longValue() != assigneeId)
      notifyRelated(
          old.longValue(), "Stable_Incidents", id, "Incident for " + horse + " was reassigned.");
    if (incident.get("groom_id") instanceof Number reporter && reporter.longValue() != assigneeId)
      notifyRelated(
          reporter.longValue(),
          "Stable_Incidents",
          id,
          "Incident for "
              + horse
              + " is assigned to "
              + role
              + ". Instructions: "
              + fields.get("assignment_note"));
    audit(managerId, "ASSIGN_STABLE_INCIDENT: " + id + " to " + role + ":" + assigneeId, ip);
    return Map.of(
        "id",
        id,
        "status",
        "InProgress",
        "assigned_to",
        assigneeId,
        "assigned_role",
        role,
        "assignment_note",
        fields.get("assignment_note"));
  }

  @Transactional
  public Map<String, Object> submitManagerIncidentResult(
      String managerEmail, String ip, UUID incidentId, JsonNode body) {
    long managerId = requireManager(managerEmail);
    String id = incidentId.toString();
    var fields = VetInput.parse(body, "result_note:2000");
    VetInput.required(fields, "result_note");
    var rows =
        db.query(
            "SELECT i.id,i.status,i.assigned_to,i.groom_id,h.horse_name FROM stable_incidents i "
                + "JOIN horses h ON h.id=i.horse_id WHERE i.id=? FOR UPDATE OF i",
            VetRows.MAPPER,
            id);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Incident not found");
    Map<String, Object> incident = rows.getFirst();
    if (!"InProgress".equals(incident.get("status"))
        || !(incident.get("assigned_to") instanceof Number assigned)
        || assigned.longValue() != managerId)
      throw new ResponseStatusException(
          CONFLICT, "Only the Manager assigned to this incident can record its result");
    db.update(
        "UPDATE stable_incidents SET result_note=?,result_by=?,result_at=?,status='AwaitingClosure'"
            + " WHERE id=?",
        fields.get("result_note"),
        managerId,
        time.utcNow(),
        id);
    audit(managerId, "SUBMIT_STABLE_INCIDENT_RESULT: " + id, ip);
    Object reporter = incident.get("groom_id");
    if (reporter instanceof Number recipient)
      notifyRelated(
          recipient.longValue(),
          "Stable_Incidents",
          id,
          "A result was recorded for the incident on " + incident.get("horse_name") + ".");
    return Map.of("id", id, "status", "AwaitingClosure", "result_note", fields.get("result_note"));
  }

  @Transactional
  public Map<String, Object> resolveIncident(String managerEmail, String ip, UUID incidentId) {
    long managerId = requireManager(managerEmail);
    String id = incidentId.toString();
    var rows =
        db.queryForList(
            "SELECT i.id,i.status,i.groom_id,i.assigned_to,i.result_note,h.horse_name FROM"
                + " stable_incidents i JOIN horses h ON h.id=i.horse_id WHERE i.id=? FOR UPDATE OF"
                + " i",
            id);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Incident not found");
    if (!"AwaitingClosure".equals(rows.getFirst().get("status")))
      throw new ResponseStatusException(
          CONFLICT, "Incident must have a recorded result before it can be closed");
    if (rows.getFirst().get("result_note") == null)
      throw new ResponseStatusException(CONFLICT, "Incident result is required before closing");
    db.update(
        "UPDATE stable_incidents SET status='Resolved',reviewed_by=?,resolved_at=? WHERE id=?",
        managerId,
        time.utcNow(),
        id);
    Object groomId = rows.getFirst().get("groom_id");
    if (groomId instanceof Number recipient)
      notifyRelated(
          recipient.longValue(),
          "Stable_Incidents",
          id,
          "Your incident report for " + rows.getFirst().get("horse_name") + " has been resolved.");
    Object assignee = rows.getFirst().get("assigned_to");
    if (assignee instanceof Number recipient
        && (!(groomId instanceof Number reporter) || reporter.longValue() != recipient.longValue()))
      notifyRelated(
          recipient.longValue(),
          "Stable_Incidents",
          id,
          "The incident assigned to you has been closed.");
    audit(managerId, "RESOLVE_STABLE_INCIDENT: " + id, ip);
    return Map.of("id", id, "status", "Resolved");
  }

  @Transactional
  public Map<String, Object> addFinancialTransaction(
      String managerEmail, String ip, JsonNode body) {
    long managerId = requireManager(managerEmail);
    var fields =
        VetInput.parse(
            body, "horse_id:uuid", "transaction_type:30", "amount:number", "billing_period:7");
    VetInput.required(fields, "horse_id", "transaction_type", "amount", "billing_period");
    String horseId = fields.get("horse_id").toString();
    String type = fields.get("transaction_type").toString();
    BigDecimal amount = (BigDecimal) fields.get("amount");
    String period = fields.get("billing_period").toString();
    if (!List.of("Care", "Medical", "Prize", "Other").contains(type))
      throw VetException.invalid("transaction_type", "Values: Care, Medical, Prize, Other");
    if (amount.signum() <= 0 || amount.scale() > 2)
      throw VetException.invalid("amount", "Must be greater than zero with at most two decimals");
    if (!period.matches("\\d{4}-(0[1-9]|1[0-2])"))
      throw VetException.invalid("billing_period", "Expected YYYY-MM");
    if (db.queryForObject(
            "SELECT count(*) FROM horses WHERE id=? AND deleted_at IS NULL", Long.class, horseId)
        == 0) throw new ResponseStatusException(NOT_FOUND, "Horse not found");
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
    return Map.of(
        "id",
        id,
        "horse_id",
        horseId,
        "transaction_type",
        type,
        "amount",
        amount,
        "billing_period",
        period);
  }

  private void validateReportRange(LocalDate from, LocalDate to) {
    if (to.isBefore(from))
      throw new ResponseStatusException(BAD_REQUEST, "to must be on or after from");
    if (from.isBefore(time.today().minusYears(5)) || to.isAfter(time.today().plusDays(60)))
      throw new ResponseStatusException(
          BAD_REQUEST, "Report dates must be within the last five years and next 60 days");
  }

  private void validatePage(int page, int limit) {
    if (page < 1 || limit < 1 || limit > 100)
      throw new ResponseStatusException(BAD_REQUEST, "page must be >=1 and limit 1-100");
  }

  private long requireManager(String email) {
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id WHERE"
                + " lower(u.email)=lower(?) AND r.role_name='CLUB_MANAGER' AND u.status='APPROVED'"
                + " AND u.must_change_password=FALSE AND u.deleted_at IS NULL",
            email);
    if (rows.isEmpty())
      throw new ResponseStatusException(
          FORBIDDEN, "Chỉ Club Manager đã được duyệt mới được thao tác");
    return ((Number) rows.getFirst().get("user_id")).longValue();
  }

  private Map<String, Object> findUser(Long userId) {
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT u.user_id,u.status,r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id"
                + " WHERE u.user_id=? AND u.deleted_at IS NULL",
            userId);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy tài khoản");
    return rows.getFirst();
  }

  private void revokeActiveRefreshTokens(Long userId) {
    db.update("UPDATE refresh_tokens SET revoked=TRUE WHERE user_id=? AND revoked=FALSE", userId);
  }

  private void assertNoUpcomingGroomAssignments(Long userId) {
    Long sessions =
        db.queryForObject(
            "SELECT count(*) FROM training_schedules ts "
                + "JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                + "WHERE ts.assigned_groom_id=? AND ts.status IN ('Scheduled','Blocked') "
                + "AND ce.event_date>=?",
            Long.class,
            userId,
            time.today());
    Long careTasks =
        db.queryForObject(
            "SELECT count(*) FROM daily_task_logs t "
                + "JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                + "WHERE t.groom_id=? AND t.status='Pending' AND ce.status<>'Cancelled' "
                + "AND ce.event_date>=?",
            Long.class,
            userId,
            time.today());
    long total = (sessions == null ? 0 : sessions) + (careTasks == null ? 0 : careTasks);
    if (total > 0)
      throw new ResponseStatusException(
          CONFLICT,
          "Phai phan cong lai hoac huy "
              + total
              + " buoi tap/cong viec sap toi truoc khi khoa/doi vai tro Groom");
  }

  private void ensurePendingStaff(Map<String, Object> user) {
    if (!"PENDING".equals(user.get("status")))
      throw new ResponseStatusException(CONFLICT, "Tài khoản không ở trạng thái PENDING");
    if (!STAFF_ROLES.contains(user.get("role_name")))
      throw new ResponseStatusException(CONFLICT, "Horse Owner không thuộc danh sách chờ duyệt");
  }

  private String normalizeStaffRole(String role) {
    if (!StringUtils.hasText(role)) return null;
    String normalized = role.trim().toUpperCase(Locale.ROOT);
    if (!STAFF_ROLES.contains(normalized))
      throw new ResponseStatusException(
          BAD_REQUEST, "role chỉ nhận HEAD_TRAINER, VETERINARIAN, HEAD_VETERINARIAN hoặc GROOM");
    return normalized;
  }

  private void notifyUser(Long userId, String message) {
    notifyUser(userId, "users", String.valueOf(userId), message);
  }

  private void notifyUser(Long userId, String relatedTable, String relatedId, String message) {
    Notification notification = new Notification();
    notification.setId(UUID.randomUUID().toString());
    notification.setUserId(userId);
    notification.setRelatedTable(relatedTable);
    notification.setRelatedId(relatedId);
    notification.setMessage(message);
    notification.setRead(false);
    notification.setCreatedAt(time.utcNow());
    notificationRepository.saveAndFlush(notification);
  }

  private void notifyActiveUsersByRole(
      String roleName, String relatedTable, String relatedId, String message) {
    var ids =
        db.queryForList(
            "SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE r.role_name=? AND u.status='APPROVED' AND u.must_change_password=FALSE "
                + "AND u.deleted_at IS NULL ORDER BY u.user_id",
            Long.class,
            roleName);
    for (long userId : ids) notifyUser(userId, relatedTable, relatedId, message);
  }

  private void notifyActiveOwner(
      Long ownerId, String relatedTable, String relatedId, String message) {
    if (ownerId == null) return;
    var ids =
        db.queryForList(
            "SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE u.user_id=? AND r.role_name='HORSE_OWNER' AND u.status='APPROVED' "
                + "AND u.must_change_password=FALSE AND u.deleted_at IS NULL",
            Long.class,
            ownerId);
    for (long id : ids) notifyUser(id, relatedTable, relatedId, message);
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

  private void notifyAccount(
      Long userId,
      com.example.springbootbackend.service.NotificationContent.Type type,
      Map<String, ?> params) {
    notifications.schedule(userId, "users", String.valueOf(userId), type, params, time.now());
  }

  private void notifyRelated(long userId, String table, String id, String message) {
    notifications.schedule(userId, table, id, message, time.now());
  }
}
