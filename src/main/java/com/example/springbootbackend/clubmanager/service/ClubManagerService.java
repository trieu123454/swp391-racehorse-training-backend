package com.example.springbootbackend.clubmanager.service;

import com.example.springbootbackend.clubmanager.dto.PendingUserPageResponse;
import com.example.springbootbackend.clubmanager.dto.PendingUserResponse;
import com.example.springbootbackend.clubmanager.dto.UserApprovalResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.*;

@Service
public class ClubManagerService {
    private static final List<String> STAFF_ROLES = List.of("HEAD_TRAINER", "VETERINARIAN", "GROOM");
    private static final List<String> ASSIGNABLE_ROLES = List.of("HEAD_TRAINER", "VETERINARIAN", "GROOM", "HORSE_OWNER");

    private final JdbcTemplate db;

    public ClubManagerService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public PendingUserPageResponse pendingUsers(String managerEmail, String role, int page, int limit) {
        long managerId = requireManager(managerEmail);
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
        var args = new java.util.ArrayList<Object>(countArgs);
        args.add(limit);
        args.add((long) (page - 1) * limit);
        List<PendingUserResponse> data = db.query(listSql, args.toArray(), (rs, rowNum) -> new PendingUserResponse(
                rs.getLong("user_id"), rs.getString("full_name"), rs.getString("email"),
                rs.getString("phone"), rs.getString("role_name"), rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime()));
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
        var args = new java.util.ArrayList<Object>();
        args.add(requestedStatus);
        if (normalizedRole != null) args.add(normalizedRole);
        return db.query("SELECT u.user_id,u.full_name,u.email,u.phone,r.role_name,u.status,u.created_at "
                + "FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.status=? AND u.deleted_at IS NULL"
                + roleClause + " ORDER BY u.full_name,u.user_id", args.toArray(), (rs, rowNum) -> new PendingUserResponse(
                rs.getLong("user_id"), rs.getString("full_name"), rs.getString("email"),
                rs.getString("phone"), rs.getString("role_name"), rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime()));
    }

    @Transactional
    public UserApprovalResponse approve(String managerEmail, Long userId) {
        long managerId = requireManager(managerEmail);
        Map<String, Object> user = findUser(userId);
        ensurePendingStaff(user);
        LocalDateTime now = LocalDateTime.now();
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
        LocalDateTime now = LocalDateTime.now();
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
        LocalDateTime now = LocalDateTime.now();
        Long userId = ((Number) request.get("user_id")).longValue();
        if (normalizedAction.equals("approve")) {
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
        String targetRole = (String) user.get("role_name");
        if (managerId == userId || "CLUB_MANAGER".equals(targetRole)) {
            throw new ResponseStatusException(FORBIDDEN, "Không thể khóa Club Manager hoặc chính tài khoản của bạn");
        }
        if (!"APPROVED".equals(user.get("status"))) {
            throw new ResponseStatusException(CONFLICT, "Chỉ được khóa tài khoản đã APPROVED");
        }
        LocalDateTime now = LocalDateTime.now();
        db.update("UPDATE users SET status='LOCKED', updated_at=? WHERE user_id=? AND status='APPROVED'", now, userId);
        String message = StringUtils.hasText(reason) ? "Tài khoản đã bị khóa. Lý do: " + reason.trim() : "Tài khoản của bạn đã bị khóa.";
        notifyUser(userId, message);
        audit(managerId, "LOCK_USER: " + userId + (StringUtils.hasText(reason) ? " - " + reason.trim() : ""));
        return Map.of("id", userId, "status", "LOCKED");
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

        Integer targetRoleId = db.queryForObject("SELECT role_id FROM roles WHERE role_name=?", Integer.class, normalizedRole);
        LocalDateTime now = LocalDateTime.now();
        db.update("UPDATE users SET role_id=?,updated_at=? WHERE user_id=? AND status='APPROVED'", targetRoleId, now, userId);
        notifyUser(userId, "Vai trò của bạn đã được Club Manager đổi từ " + currentRole + " sang " + normalizedRole + ". Vui lòng đăng nhập lại để áp dụng quyền mới.");
        audit(managerId, "UPDATE_USER_ROLE: " + userId + " " + currentRole + " -> " + normalizedRole);
        return Map.of("id", userId, "previous_role", currentRole, "role_name", normalizedRole);
    }

    private long requireManager(String email) {
        List<Map<String, Object>> rows = db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id WHERE lower(u.email)=lower(?) AND r.role_name='CLUB_MANAGER' AND u.status='APPROVED' AND u.deleted_at IS NULL", email);
        if (rows.isEmpty()) throw new ResponseStatusException(FORBIDDEN, "Chỉ Club Manager đã được duyệt mới được thao tác");
        return ((Number) rows.getFirst().get("user_id")).longValue();
    }

    private Map<String, Object> findUser(Long userId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT u.user_id,u.status,r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=? AND u.deleted_at IS NULL", userId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy tài khoản");
        return rows.getFirst();
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
        db.update("INSERT INTO notifications(id,user_id,related_table,related_id,message,is_read,created_at) VALUES (?,?,?,?,?,FALSE,?)",
                UUID.randomUUID().toString(), userId, "users", String.valueOf(userId), message, LocalDateTime.now());
    }

    private void audit(long managerId, String action) {
        db.update("INSERT INTO audit_logs(id,user_id,action_performed,created_at) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(), managerId, action, LocalDateTime.now());
    }
}
