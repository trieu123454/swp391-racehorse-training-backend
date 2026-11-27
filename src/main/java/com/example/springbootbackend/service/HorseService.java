package com.example.springbootbackend.service;

import static org.springframework.http.HttpStatus.*;

import com.example.springbootbackend.dto.request.HorseRequest;
import com.example.springbootbackend.dto.request.HorseUpdateRequest;
import com.example.springbootbackend.entity.AppUser;
import com.example.springbootbackend.entity.AuditLog;
import com.example.springbootbackend.entity.Horse;
import com.example.springbootbackend.entity.HorseRegistrationRequest;
import com.example.springbootbackend.entity.Notification;
import com.example.springbootbackend.repository.AppUserRepository;
import com.example.springbootbackend.repository.AuditLogRepository;
import com.example.springbootbackend.repository.HorseRegistrationRequestRepository;
import com.example.springbootbackend.repository.HorseRepository;
import com.example.springbootbackend.repository.NotificationRepository;
import com.example.springbootbackend.repository.StableBoxRepository;
import com.example.springbootbackend.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Manages horse profiles, stable assignments, and ownership history. */
@Service
public class HorseService {
  private static final long LIST_CACHE_TTL_MS = 20_000L;
  private static final long STABLES_CACHE_TTL_MS = 60_000L;
  private static final int MAX_ACTIVE_SUPPLEMENT_DOCS = 20;
  private static final Set<String> SUPPLEMENT_DOCUMENT_TYPES =
      Set.of("HORSE_CERTIFICATE", "HEALTH_DOCUMENT", "OTHER");

  private static final class CachedValue<T> {
    private final T value;
    private final long expiresAtMs;

    CachedValue(T value, long expiresAtMs) {
      this.value = value;
      this.expiresAtMs = expiresAtMs;
    }

    T value() {
      return value;
    }

    long expiresAtMs() {
      return expiresAtMs;
    }
  }

  private static final ConcurrentHashMap<String, CachedValue<Map<String, Object>>> listCache =
      new ConcurrentHashMap<>();
  private static volatile CachedValue<List<Map<String, Object>>> stablesCache;

  public static void invalidateCache() {
    listCache.clear();
    stablesCache = null;
  }

  private final VetData db;
  private final VetTime time;
  private final int groomAssignmentWindowDays;
  private final AuditLogRepository auditLogRepository;
  private final HorseRepository horseRepository;
  private final HorseRegistrationRequestRepository registrationRequestRepository;
  private final NotificationRepository notificationRepository;
  private final AppUserRepository userRepository;
  private final StableBoxRepository stableBoxRepository;
  private final TrainingSimulationRecorder simulationRecorder;
  private final ObjectMapper objectMapper;

  public HorseService(
      VetData db,
      VetTime time,
      @Value("${app.groom.assignment-window-days:14}") int groomAssignmentWindowDays,
      AuditLogRepository auditLogRepository,
      HorseRepository horseRepository,
      HorseRegistrationRequestRepository registrationRequestRepository,
      NotificationRepository notificationRepository,
      AppUserRepository userRepository,
      StableBoxRepository stableBoxRepository,
      TrainingSimulationRecorder simulationRecorder,
      ObjectMapper objectMapper) {
    this.db = db;
    this.time = time;
    this.groomAssignmentWindowDays = Math.max(0, groomAssignmentWindowDays);
    this.auditLogRepository = auditLogRepository;
    this.horseRepository = horseRepository;
    this.registrationRequestRepository = registrationRequestRepository;
    this.notificationRepository = notificationRepository;
    this.userRepository = userRepository;
    this.stableBoxRepository = stableBoxRepository;
    this.simulationRecorder = simulationRecorder;
    this.objectMapper = objectMapper;
  }

  public record Actor(long id, String role) {}

  public Actor actor(String email, boolean write) {
    Actor actor = actorFromSecurityContext(email);
    if (actor == null) {
      var rows =
          db.queryForList(
              "SELECT u.user_id,u.status,u.deleted_at,u.must_change_password,r.role_name FROM users"
                  + " u JOIN roles r ON r.role_id=u.role_id WHERE lower(u.email)=lower(?)",
              email);
      if (rows.isEmpty()) throw new ResponseStatusException(FORBIDDEN, "Tài khoản chưa được duyệt");
      var user = rows.getFirst();
      if (!"APPROVED".equals(user.get("status")) || user.get("deleted_at") != null)
        throw new ResponseStatusException(FORBIDDEN, "Tài khoản chưa được duyệt");
      actor = new Actor(((Number) user.get("user_id")).longValue(), (String) user.get("role_name"));
      if (Boolean.TRUE.equals(user.get("must_change_password")))
        throw new ResponseStatusException(FORBIDDEN, "Change password before using business APIs");
    }
    if (write
        ? !actor.role().equals("CLUB_MANAGER")
        : !Set.of(
                "CLUB_MANAGER",
                "HEAD_TRAINER",
                "VETERINARIAN",
                "HEAD_VETERINARIAN",
                "GROOM",
                "HORSE_OWNER")
            .contains(actor.role()))
      throw new ResponseStatusException(FORBIDDEN, "Không có quyền truy cập");
    return actor;
  }

  private Actor actorFromSecurityContext(String email) {
    JwtAuthenticationFilter.VerifiedActor verified = JwtAuthenticationFilter.currentActor(email);
    return verified == null ? null : new Actor(verified.userId(), verified.role());
  }

  public Actor ownerLookupActor(String email) {
    Actor actor = actor(email, false);
    if (!Set.of("CLUB_MANAGER", "HEAD_TRAINER").contains(actor.role()))
      throw new ResponseStatusException(FORBIDDEN, "Không có quyền truy cập");
    return actor;
  }

  public Map<String, Object> list(
      Actor actor,
      String search,
      String breed,
      String status,
      UUID stable,
      Boolean locked,
      int page,
      int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new ResponseStatusException(BAD_REQUEST, "Phân trang không hợp lệ");
    String cacheKey =
        actor.role()
            + "|"
            + (Set.of("HORSE_OWNER", "GROOM").contains(actor.role()) ? actor.id() : "*")
            + "|"
            + Objects.toString(search, "")
            + "|"
            + Objects.toString(breed, "")
            + "|"
            + Objects.toString(status, "")
            + "|"
            + Objects.toString(stable, "")
            + "|"
            + Objects.toString(locked, "")
            + "|"
            + page
            + "|"
            + size;
    long nowMs = System.currentTimeMillis();
    CachedValue<Map<String, Object>> cached = listCache.get(cacheKey);
    if (cached != null && nowMs < cached.expiresAtMs()) {
      return cached.value();
    }
    StringBuilder where = new StringBuilder(" WHERE h.deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    if (actor.role().equals("HORSE_OWNER")) {
      where.append(" AND h.owner_id=?");
      args.add(actor.id());
    }
    if (actor.role().equals("GROOM")) {
      LocalDate today = time.today();
      LocalDate from = today.minusDays(groomAssignmentWindowDays);
      LocalDate through = today.plusDays(groomAssignmentWindowDays);
      where.append(
          " AND (EXISTS (SELECT 1 FROM training_schedules ts JOIN calendar_events ce ON"
              + " ce.id=ts.calendar_event_id WHERE ts.horse_id=h.id AND ts.assigned_groom_id=? AND"
              + " (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?)"
              + " OR EXISTS (SELECT 1 FROM daily_task_logs t JOIN calendar_events ce ON"
              + " ce.id=t.calendar_event_id WHERE t.horse_id=h.id AND t.groom_id=? AND (ce.status"
              + " IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?))");
      args.add(actor.id());
      args.add(from);
      args.add(through);
      args.add(actor.id());
      args.add(from);
      args.add(through);
    }
    if (search != null && !search.isBlank()) {
      String pattern = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
      where.append(
          " AND (lower(h.horse_name) LIKE ? ESCAPE '!'"
              + " OR lower(COALESCE(h.breed,'')) LIKE ? ESCAPE '!'"
              + " OR lower(COALESCE(h.microchip_number,'')) LIKE ? ESCAPE '!'"
              + " OR lower(COALESCE(h.passport_number,'')) LIKE ? ESCAPE '!')");
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    if (breed != null) {
      where.append(" AND h.breed=?");
      args.add(breed);
    }
    if (status != null) {
      where.append(" AND h.current_status=?");
      args.add(status);
    }
    if (stable != null) {
      where.append(" AND h.stable_box_id=?");
      args.add(stable.toString());
    }
    if (locked != null) {
      where.append(" AND h.is_training_locked=?");
      args.add(locked);
    }
    List<Object> queryArgs = new ArrayList<>(args);
    queryArgs.add(size);
    queryArgs.add((long) page * size);
    String latestMetricWeight =
        "CASE WHEN h.current_weight_kg IS NOT NULL THEN h.current_weight_kg ELSE (SELECT"
            + " m.body_weight_kg FROM shared_training_metrics m WHERE m.horse_id=h.id AND"
            + " m.body_weight_kg IS NOT NULL ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1) END AS"
            + " latest_metric_weight_kg";
    String columns =
        actor.role().equals("GROOM")
            ? "h.id,h.horse_name,h.image_url,h.stable_box_id,h.current_status,h.height_cm,h.current_weight_kg,s.box_code,s.section,"
                + latestMetricWeight
                + ",COUNT(*) OVER() AS _total_count"
            : "h.*,s.box_code,s.section,u.full_name AS owner_name,"
                + latestMetricWeight
                + ",COUNT(*) OVER() AS _total_count";
    var rawItems =
        db.queryForList(
            "SELECT "
                + columns
                + " FROM horses h LEFT JOIN stable_boxes s ON s.id=h.stable_box_id LEFT JOIN users"
                + " u ON u.user_id=h.owner_id"
                + where
                + " ORDER BY h.created_at DESC,h.id LIMIT ? OFFSET ?",
            queryArgs.toArray());
    long total;
    if (!rawItems.isEmpty() && rawItems.getFirst().get("_total_count") instanceof Number n) {
      total = n.longValue();
    } else if (page > 0) {
      total =
          db.queryForObject("SELECT count(*) FROM horses h" + where, Long.class, args.toArray());
    } else {
      total = 0L;
    }
    var items =
        rawItems.stream()
            .map(
                row -> {
                  row.remove("_total_count");
                  Object latestWeight = row.remove("latest_metric_weight_kg");
                  if (row.get("current_weight_kg") == null && latestWeight != null)
                    row.put("current_weight_kg", latestWeight);
                  if (actor.role().equals("GROOM")) return groomHorseSummary(row);
                  if (row.get("stable_box_id") == null) {
                    row.put("stable_box", null);
                  } else {
                    Map<String, Object> stableBox = new LinkedHashMap<>();
                    stableBox.put("id", row.get("stable_box_id"));
                    stableBox.put("box_code", row.get("box_code"));
                    stableBox.put("section", row.get("section"));
                    row.put("stable_box", stableBox);
                  }
                  return row;
                })
            .toList();
    Map<String, Object> result =
        Map.of(
            "items", items, "data", items, "total", total, "page", page, "size", size, "limit",
            size);
    listCache.put(
        cacheKey, new CachedValue<>(result, System.currentTimeMillis() + LIST_CACHE_TTL_MS));
    return result;
  }

  public Map<String, Object> detail(Actor actor, UUID id) {
    if (actor.role().equals("GROOM")) {
      if (!groomHasAssignment(actor.id(), id.toString()))
        throw new ResponseStatusException(FORBIDDEN, "Horse is not assigned to this Groom");
      var assigned =
          db.queryForList(
              "SELECT"
                  + " h.id,h.horse_name,h.image_url,h.stable_box_id,h.current_status,h.height_cm,h.current_weight_kg,s.box_code,s.section,CASE"
                  + " WHEN h.current_weight_kg IS NOT NULL THEN h.current_weight_kg ELSE (SELECT"
                  + " m.body_weight_kg FROM shared_training_metrics m WHERE m.horse_id=h.id AND"
                  + " m.body_weight_kg IS NOT NULL ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1)"
                  + " END AS latest_metric_weight_kg FROM horses h LEFT JOIN stable_boxes s ON"
                  + " s.id=h.stable_box_id WHERE h.id=? AND h.deleted_at IS NULL",
              id.toString());
      if (assigned.isEmpty())
        throw new ResponseStatusException(FORBIDDEN, "Horse is not assigned to this Groom");
      return groomHorseSummary(assigned.getFirst());
    }
    var rows =
        db.queryForList(
            "SELECT h.*,s.box_code,s.section,u.full_name AS owner_name,CASE WHEN"
                + " h.current_weight_kg IS NOT NULL THEN h.current_weight_kg ELSE (SELECT"
                + " m.body_weight_kg FROM shared_training_metrics m WHERE m.horse_id=h.id AND"
                + " m.body_weight_kg IS NOT NULL ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1) END"
                + " AS latest_metric_weight_kg FROM horses h LEFT JOIN stable_boxes s ON"
                + " s.id=h.stable_box_id LEFT JOIN users u ON u.user_id=h.owner_id WHERE h.id=? AND"
                + " h.deleted_at IS NULL",
            id.toString());
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa");
    var horse = rows.getFirst();
    Object latestWeight = horse.remove("latest_metric_weight_kg");
    if (horse.get("current_weight_kg") == null && latestWeight != null)
      horse.put("current_weight_kg", latestWeight);
    if (actor.role().equals("HORSE_OWNER")
        && (!(horse.get("owner_id") instanceof Number owner) || owner.longValue() != actor.id()))
      throw new ResponseStatusException(FORBIDDEN, "Ngựa không thuộc sở hữu của bạn");
    horse.put(
        "stable_box",
        horse.get("stable_box_id") == null
            ? null
            : Map.of(
                "id",
                horse.get("stable_box_id"),
                "box_code",
                Objects.toString(horse.get("box_code"), "")));
    horse.put(
        "owner",
        horse.get("owner_id") == null
            ? null
            : Map.of("id", horse.get("owner_id"), "full_name", horse.get("owner_name")));
    return horse;
  }

  public List<Map<String, Object>> approvedRegistrationDocuments(Actor actor, UUID horseId) {
    if (!Set.of("CLUB_MANAGER", "HORSE_OWNER").contains(actor.role()))
      throw new ResponseStatusException(FORBIDDEN, "Không có quyền xem giấy tờ sở hữu");
    detail(actor, horseId);
    return approvedRegistrationDocumentsForVerifiedHorse(actor, horseId);
  }

  public List<Map<String, Object>> approvedRegistrationDocumentsForVerifiedHorse(
      Actor actor, UUID horseId) {
    if (!Set.of("CLUB_MANAGER", "HORSE_OWNER").contains(actor.role()))
      throw new ResponseStatusException(FORBIDDEN, "Không có quyền xem giấy tờ sở hữu");
    String ownerFilter = actor.role().equals("HORSE_OWNER") ? " AND r.owner_id=?" : "";
    List<Object> args = new ArrayList<>();
    args.add(horseId.toString());
    if (actor.role().equals("HORSE_OWNER")) args.add(actor.id());
    var registrationRows =
        db.queryForList(
            "SELECT d.id,d.original_filename AS"
                + " name,d.content_type,d.file_size,d.document_type,d.revision_no,d.uploaded_at,d.object_path"
                + " FROM horse_registration_documents d JOIN horse_registration_requests r ON"
                + " r.id=d.request_id WHERE r.approved_horse_id=? AND r.status='APPROVED'"
                + ownerFilter
                + " ORDER BY d.uploaded_at DESC",
            args.toArray());

    String revokedFilter = actor.role().equals("HORSE_OWNER") ? " AND d.revoked_at IS NULL" : "";
    var supplementRows =
        db.queryForList(
            "SELECT d.id,d.original_filename AS"
                + " name,d.content_type,d.file_size,d.document_type,1 AS"
                + " revision_no,d.uploaded_at,d.object_path,d.note,d.revoked_at,d.revoke_reason,"
                + "up.full_name AS uploaded_by_name,rev.full_name AS revoked_by_name"
                + " FROM horse_supplement_documents d LEFT JOIN users up ON"
                + " up.user_id=d.uploaded_by LEFT JOIN users rev ON rev.user_id=d.revoked_by"
                + " WHERE d.horse_id=?"
                + revokedFilter
                + " ORDER BY d.uploaded_at DESC",
            horseId.toString());

    List<Map<String, Object>> combined = new ArrayList<>(registrationRows.size() + supplementRows.size());
    for (var row : registrationRows) {
      Map<String, Object> item = new LinkedHashMap<>(row);
      item.put("source", "REGISTRATION");
      item.put("note", null);
      item.put("uploaded_by_name", null);
      item.put("revoked_at", null);
      item.put("revoked_by_name", null);
      item.put("revoke_reason", null);
      item.put("can_revoke", false);
      combined.add(item);
    }
    for (var row : supplementRows) {
      Map<String, Object> item = new LinkedHashMap<>(row);
      item.put("source", "MANAGER_SUPPLEMENT");
      item.put("can_revoke", actor.role().equals("CLUB_MANAGER") && row.get("revoked_at") == null);
      combined.add(item);
    }
    combined.sort(
        (left, right) -> {
          String a = Objects.toString(left.get("uploaded_at"), "");
          String b = Objects.toString(right.get("uploaded_at"), "");
          return b.compareTo(a);
        });
    return combined;
  }

  public String validateSupplementDocumentInput(
      Actor actor, UUID horseId, String documentType, String note) {
    if (!actor.role().equals("CLUB_MANAGER")) {
      throw new ResponseStatusException(FORBIDDEN, "Chỉ Quản lý CLB mới có quyền bổ sung giấy tờ");
    }
    horseRepository
        .findByIdAndDeletedAtIsNull(horseId.toString())
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa"));
    String normalized =
        documentType == null || documentType.isBlank()
            ? "OTHER"
            : documentType.trim().toUpperCase(Locale.ROOT);
    if ("OWNERSHIP_PROOF".equals(normalized)) {
      throw new ResponseStatusException(
          BAD_REQUEST, "Không thể bổ sung loại chứng minh quyền sở hữu sau khi hồ sơ đã duyệt");
    }
    if (!SUPPLEMENT_DOCUMENT_TYPES.contains(normalized)) {
      throw new ResponseStatusException(BAD_REQUEST, "Loại giấy tờ bổ sung không hợp lệ");
    }
    if (note != null && note.trim().length() > 500) {
      throw new ResponseStatusException(BAD_REQUEST, "Ghi chú giấy tờ không được vượt quá 500 ký tự");
    }
    Long activeCount =
        db.queryForObject(
            "SELECT count(*) FROM horse_supplement_documents WHERE horse_id=? AND revoked_at IS NULL",
            Long.class,
            horseId.toString());
    if (activeCount != null && activeCount >= MAX_ACTIVE_SUPPLEMENT_DOCS) {
      throw new ResponseStatusException(
          BAD_REQUEST, "Hồ sơ đã đạt tối đa 20 giấy tờ bổ sung còn hiệu lực");
    }
    return normalized;
  }

  @Transactional
  public Map<String, Object> saveSupplementDocument(
      Actor actor,
      UUID horseId,
      String objectPath,
      String originalFilename,
      String contentType,
      long fileSize,
      String documentType,
      String note) {
    String normalizedType = validateSupplementDocumentInput(actor, horseId, documentType, note);
    Horse horse =
        horseRepository
            .findActiveByIdForUpdate(horseId.toString())
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa"));
    Long activeCount =
        db.queryForObject(
            "SELECT count(*) FROM horse_supplement_documents WHERE horse_id=? AND revoked_at IS NULL",
            Long.class,
            horseId.toString());
    if (activeCount != null && activeCount >= MAX_ACTIVE_SUPPLEMENT_DOCS) {
      throw new ResponseStatusException(
          BAD_REQUEST, "Hồ sơ đã đạt tối đa 20 giấy tờ bổ sung còn hiệu lực");
    }
    String safeFilename =
        originalFilename == null || originalFilename.isBlank()
            ? "giay-to"
            : originalFilename.trim();
    if (safeFilename.length() > 255) safeFilename = safeFilename.substring(0, 255);
    String safeContentType =
        contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.trim();
    if (safeContentType.length() > 100) safeContentType = safeContentType.substring(0, 100);
    String safeNote = note == null || note.isBlank() ? null : note.trim();
    String docId = UUID.randomUUID().toString();
    LocalDateTime now = time.utcNow();
    db.update(
        "INSERT INTO"
            + " horse_supplement_documents(id,horse_id,object_path,original_filename,content_type,file_size,document_type,note,uploaded_by,uploaded_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        docId,
        horseId.toString(),
        objectPath,
        safeFilename,
        safeContentType,
        fileSize,
        normalizedType,
        safeNote,
        actor.id(),
        now);
    audit(actor.id(), "ADD_HORSE_DOCUMENT:" + horseId + ":" + docId);
    if (horse.getOwnerId() != null) {
      Notification notification = new Notification();
      notification.setId(UUID.randomUUID().toString());
      notification.setUserId(horse.getOwnerId());
      notification.setRelatedTable("horses");
      notification.setRelatedId(horseId.toString());
      notification.setMessage(
          "Quản lý CLB vừa bổ sung giấy tờ ("
              + supplementDocumentTypeLabel(normalizedType)
              + ") vào hồ sơ ngựa "
              + horse.getHorseName()
              + ".");
      notification.setRead(false);
      notification.setCreatedAt(now);
      notificationRepository.saveAndFlush(notification);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", docId);
    result.put("name", safeFilename);
    result.put("content_type", safeContentType);
    result.put("file_size", fileSize);
    result.put("document_type", normalizedType);
    result.put("revision_no", 1);
    result.put("uploaded_at", now);
    result.put("object_path", objectPath);
    result.put("note", safeNote);
    result.put("uploaded_by_name", findUserName(actor.id()));
    result.put("revoked_at", null);
    result.put("revoked_by_name", null);
    result.put("revoke_reason", null);
    result.put("source", "MANAGER_SUPPLEMENT");
    result.put("can_revoke", true);
    return result;
  }

  @Transactional
  public Map<String, Object> revokeSupplementDocument(
      Actor actor, UUID horseId, UUID docId, String reason) {
    if (!actor.role().equals("CLUB_MANAGER")) {
      throw new ResponseStatusException(FORBIDDEN, "Chỉ Quản lý CLB mới có quyền thu hồi giấy tờ bổ sung");
    }
    horseRepository
        .findActiveByIdForUpdate(horseId.toString())
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa"));
    String trimmedReason = reason == null ? "" : reason.trim();
    if (trimmedReason.length() < 3 || trimmedReason.length() > 500) {
      throw new ResponseStatusException(
          BAD_REQUEST, "Vui lòng nhập lý do thu hồi từ 3 đến 500 ký tự");
    }
    var rows =
        db.queryForList(
            "SELECT id,revoked_at FROM horse_supplement_documents WHERE id=? AND horse_id=?",
            docId.toString(),
            horseId.toString());
    if (rows.isEmpty()) {
      Long originalCount =
          db.queryForObject(
              "SELECT count(*) FROM horse_registration_documents d JOIN horse_registration_requests"
                  + " r ON r.id=d.request_id WHERE d.id=? AND r.approved_horse_id=? AND"
                  + " r.status='APPROVED'",
              Long.class,
              docId.toString(),
              horseId.toString());
      if (originalCount != null && originalCount > 0) {
        throw new ResponseStatusException(
            CONFLICT, "Không thể thu hồi giấy tờ pháp lý gốc từ hồ sơ đăng ký");
      }
      throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy giấy tờ bổ sung");
    }
    if (rows.getFirst().get("revoked_at") != null) {
      throw new ResponseStatusException(CONFLICT, "Giấy tờ này đã được thu hồi trước đó");
    }
    LocalDateTime now = time.utcNow();
    db.update(
        "UPDATE horse_supplement_documents SET revoked_at=?,revoked_by=?,revoke_reason=? WHERE"
            + " id=?",
        now,
        actor.id(),
        trimmedReason,
        docId.toString());
    audit(actor.id(), "REVOKE_HORSE_DOCUMENT:" + horseId + ":" + docId);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", docId.toString());
    response.put("status", "REVOKED");
    response.put("revoked_at", now);
    response.put("revoke_reason", trimmedReason);
    return response;
  }

  private static String supplementDocumentTypeLabel(String documentType) {
    return switch (documentType) {
      case "HORSE_CERTIFICATE" -> "Giấy chứng nhận / Hộ chiếu";
      case "HEALTH_DOCUMENT" -> "Hồ sơ sức khỏe / Kiểm dịch";
      default -> "Giấy tờ khác";
    };
  }

  private boolean groomHasAssignment(long groomId, String horseId) {
    LocalDate today = time.today();
    LocalDate from = today.minusDays(groomAssignmentWindowDays);
    LocalDate through = today.plusDays(groomAssignmentWindowDays);
    Long count =
        db.queryForObject(
            "SELECT count(*) FROM horses h WHERE h.id=? AND h.deleted_at IS NULL AND (EXISTS"
                + " (SELECT 1 FROM training_schedules ts JOIN calendar_events ce ON"
                + " ce.id=ts.calendar_event_id WHERE ts.horse_id=h.id AND ts.assigned_groom_id=?"
                + " AND (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ?"
                + " AND ?) OR EXISTS (SELECT 1 FROM daily_task_logs t JOIN calendar_events ce ON"
                + " ce.id=t.calendar_event_id WHERE t.horse_id=h.id AND t.groom_id=? AND (ce.status"
                + " IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?))",
            Long.class,
            horseId,
            groomId,
            from,
            through,
            groomId,
            from,
            through);
    return count != null && count > 0;
  }

  private Map<String, Object> groomHorseSummary(Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", row.get("id"));
    result.put("horse_name", row.get("horse_name"));
    result.put("image_url", row.get("image_url"));
    if (row.get("stable_box_id") == null) result.put("stable_box", null);
    else {
      Map<String, Object> stable = new LinkedHashMap<>();
      stable.put("box_code", row.get("box_code"));
      stable.put("section", row.get("section"));
      result.put("stable_box", stable);
    }
    result.put("current_status", row.get("current_status"));
    result.put("height_cm", row.get("height_cm"));
    result.put(
        "current_weight_kg",
        row.get("current_weight_kg") == null
            ? row.get("latest_metric_weight_kg")
            : row.get("current_weight_kg"));
    return result;
  }

  @Transactional
  public Map<String, Object> save(Actor actor, UUID id, HorseRequest request) {
    if (id == null) {
      throw new ResponseStatusException(
          FORBIDDEN, "Hồ sơ ngựa mới phải do Horse Owner gửi yêu cầu và được Club Manager duyệt.");
    }
    Horse horse =
        horseRepository
            .findActiveByIdForUpdate(id.toString())
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa"));
    if (!Objects.equals(horse.getOwnerId(), request.ownerId()))
      throw new ResponseStatusException(
          CONFLICT, "Thay đổi chủ sở hữu cần yêu cầu liên kết và sự đồng ý của chủ hiện tại.");
    if (request.birthYear() != null && request.birthYear() > time.today().getYear())
      throw new ResponseStatusException(BAD_REQUEST, "Năm sinh không được ở tương lai");
    boolean alreadyInThisBox = request.stableBoxId().toString().equals(horse.getStableBoxId());
    if (!alreadyInThisBox) {
      var box =
          stableBoxRepository
              .findByIdForUpdate(request.stableBoxId().toString())
              .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Chuồng không tồn tại"));
      long occupied =
          horseRepository.countByStableBoxIdAndDeletedAtIsNullAndIdNot(
              request.stableBoxId().toString(), id.toString());
      if (!"Available".equals(box.getStatus())
          || box.getCapacity() == null
          || occupied >= box.getCapacity())
        throw new ResponseStatusException(CONFLICT, "Chuồng đã đầy hoặc không khả dụng");
      String boxType =
          db.queryForObject(
              "SELECT COALESCE(box_type,'Normal') FROM stable_boxes WHERE id=?",
              String.class,
              request.stableBoxId().toString());
      if (!"Normal".equals(boxType))
        throw new ResponseStatusException(
            BAD_REQUEST, "Chuồng cách ly/chấn thương chỉ được điều chuyển bởi bác sĩ thú y");
    }
    String microchip = optionalIdentifier(request.microchipNumber());
    String passport = optionalIdentifier(request.passportNumber());
    String checkMicrochip =
        Objects.equals(microchip, optionalIdentifier(horse.getMicrochipNumber()))
            ? null
            : microchip;
    String checkPassport =
        Objects.equals(passport, optionalIdentifier(horse.getPassportNumber())) ? null : passport;
    if (checkMicrochip != null || checkPassport != null) {
      HorseRegistrationService.acquireIdentityLocks(db, checkMicrochip, checkPassport);
      validateIdentityAvailable(checkMicrochip, checkPassport, id.toString(), id.toString());
    }
    String path = request.imagePath();
    if (path == null) path = horse.getImageUrl();
    if (path != null && !Objects.equals(path, horse.getImageUrl())) {
      Long uploaded =
          db.queryForObject(
              "SELECT count(*) FROM audit_logs WHERE user_id=? AND action_performed=?",
              Long.class,
              actor.id(),
              "UPLOAD_HORSE_IMAGE:" + path);
      if (uploaded == null || uploaded == 0)
        throw new ResponseStatusException(
            BAD_REQUEST, "Ảnh phải được upload qua hệ thống bởi tài khoản hiện tại");
    }
    horse.setHorseName(request.horseName().trim());
    horse.setBreed(request.breed());
    horse.setGender(optionalIdentifier(request.gender()));
    horse.setBirthYear(request.birthYear());
    horse.setHeightCm(request.heightCm());
    horse.setCurrentWeightKg(request.currentWeightKg());
    horse.setPedigreeFather(request.pedigreeFather());
    horse.setPedigreeMother(request.pedigreeMother());
    horse.setMicrochipNumber(microchip);
    horse.setPassportNumber(passport);
    horse.setImageUrl(path);
    horse.setStableBoxId(request.stableBoxId().toString());
    horseRepository.saveAndFlush(horse);
    audit(actor.id(), "UPDATE_HORSE: " + id);
    return detail(actor, id);
  }

  public List<Map<String, Object>> ownershipHistory(Actor actor, UUID horseId) {
    if (!Set.of("CLUB_MANAGER", "HORSE_OWNER").contains(actor.role()))
      throw new ResponseStatusException(
          FORBIDDEN, "Ownership history requires an owner or Club Manager");
    var horse = detail(actor, horseId);
    var result = new ArrayList<Map<String, Object>>();
    Object history = horse.get("ownership_history_json");
    if (history != null && !history.toString().isBlank()) {
      try {
        result.addAll(
            objectMapper.readValue(
                history.toString(), new TypeReference<List<Map<String, Object>>>() {}));
      } catch (java.io.IOException ex) {
        throw new IllegalStateException("Cannot read ownership history", ex);
      }
    }
    result.addAll(
        db.query(
            "SELECT x.*,p.full_name AS previous_owner_name,n.full_name AS"
                + " new_owner_name,c.full_name AS changed_by_name FROM horse_ownership_history x"
                + " LEFT JOIN users p ON p.user_id=x.previous_owner_id LEFT JOIN users n ON"
                + " n.user_id=x.new_owner_id LEFT JOIN users c ON c.user_id=x.changed_by WHERE"
                + " x.horse_id=? ORDER BY x.changed_at DESC,x.id DESC",
            VetRows.MAPPER,
            horseId.toString()));
    for (var item : result) {
      item.put("previous_owner_name", findUserName(item.get("previous_owner_id")));
      item.put("new_owner_name", findUserName(item.get("new_owner_id")));
      item.put("changed_by_name", findUserName(item.get("changed_by")));
    }
    return result;
  }

  private String findUserName(Object userIdObj) {
    if (!(userIdObj instanceof Number num)) return null;
    return userRepository.findById(num.longValue()).map(AppUser::getFullName).orElse(null);
  }

  private void validateIdentityAvailable(
      String microchip, String passport, String horseId, String requestId) {
    List<String> activeRequests = List.of("PENDING", "NEEDS_INFORMATION", "AWAITING_OWNER_CONSENT");
    if (microchip != null
        && (horseRepository.existsByMicrochipNumberIgnoreCaseAndIdNotAndDeletedAtIsNull(
                microchip, horseId)
            || registrationRequestRepository.existsOtherActiveMicrochip(
                "REGISTER", activeRequests, microchip, requestId)))
      throw new ResponseStatusException(CONFLICT, "Số microchip đã được dùng cho một hồ sơ khác");
    if (passport != null
        && (horseRepository.existsByPassportNumberIgnoreCaseAndIdNotAndDeletedAtIsNull(
                passport, horseId)
            || registrationRequestRepository.existsOtherActivePassport(
                "REGISTER", activeRequests, passport, requestId)))
      throw new ResponseStatusException(CONFLICT, "Số hộ chiếu đã được dùng cho một hồ sơ khác");
  }

  private static String optionalIdentifier(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  @Transactional
  public Map<String, Object> update(Actor actor, UUID id, HorseUpdateRequest request) {
    String horseName = request.horseName().trim();
    String breed = request.breed();
    String gender = request.gender();
    Integer birthYear = request.birthYear();
    var heightCm = request.heightCm();
    var currentWeightKg = request.currentWeightKg();
    String father = request.pedigreeFather();
    String mother = request.pedigreeMother();
    String microchip = request.microchipNumber();
    String passport = request.passportNumber();
    String stable = request.stableBoxId().toString();
    Long owner = request.ownerId();
    String image = request.imagePath();
    var merged =
        new HorseRequest(
            horseName,
            breed,
            gender,
            birthYear,
            heightCm,
            currentWeightKg,
            father,
            mother,
            microchip,
            passport,
            image,
            UUID.fromString(stable),
            owner,
            request.confirmOwnerChange());
    return save(actor, id, merged);
  }

  public List<Map<String, Object>> stables() {
    long nowMs = System.currentTimeMillis();
    CachedValue<List<Map<String, Object>>> cached = stablesCache;
    if (cached != null && nowMs < cached.expiresAtMs()) {
      return cached.value();
    }
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT s.*, (SELECT count(*) FROM horses h WHERE h.stable_box_id=s.id AND h.deleted_at"
                + " IS NULL) AS occupied FROM stable_boxes s WHERE s.status='Available' AND"
                + " COALESCE(s.box_type,'Normal')='Normal' AND s.capacity > (SELECT count(*) FROM"
                + " horses h WHERE h.stable_box_id=s.id AND h.deleted_at IS NULL) ORDER BY"
                + " s.box_code");
    stablesCache = new CachedValue<>(rows, nowMs + STABLES_CACHE_TTL_MS);
    return rows;
  }

  public Map<String, Object> owners(String search, int page, int size) {
    if (page < 0 || size < 1 || size > 50)
      throw new ResponseStatusException(BAD_REQUEST, "Phân trang chủ sở hữu không hợp lệ");
    if (search == null || search.isBlank()) return ownerPage(List.of(), false, page, size);
    String term = search.trim();
    String idTerm = term.startsWith("#") ? term.substring(1) : term;
    Long exactId = null;
    if (idTerm.matches("\\d+")) {
      try {
        exactId = Long.parseLong(idTerm);
      } catch (NumberFormatException ignored) {
        /* Keep searching the text fields. */ }
    }
    boolean searchByIdOnly = exactId != null && idTerm.length() < 3;
    if (term.length() < 3 && !searchByIdOnly) return ownerPage(List.of(), false, page, size);
    String sql =
        "SELECT u.user_id,u.full_name,u.email,u.phone FROM users u JOIN roles r ON"
            + " r.role_id=u.role_id WHERE r.role_name='HORSE_OWNER' AND u.status='APPROVED' AND"
            + " u.deleted_at IS NULL AND ";
    List<Object> args = new ArrayList<>();
    if (searchByIdOnly) {
      sql += "u.user_id=? ";
      args.add(exactId);
    } else {
      String textTerm = exactId == null ? term : idTerm;
      String pattern = "%" + escapeLike(textTerm.toLowerCase(Locale.ROOT)) + "%";
      sql +=
          "(lower(u.full_name) LIKE ? ESCAPE '!' "
              + "OR lower(u.email) LIKE ? ESCAPE '!' "
              + "OR lower(COALESCE(u.phone,'')) LIKE ? ESCAPE '!'";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      if (exactId != null) {
        sql += " OR u.user_id=?";
        args.add(exactId);
      }
      sql += ") ";
    }
    sql += "ORDER BY u.full_name,u.user_id LIMIT ? OFFSET ?";
    args.add(size + 1);
    args.add((long) page * size);
    List<Map<String, Object>> rows = db.queryForList(sql, args.toArray());
    boolean hasMore = rows.size() > size;
    List<Map<String, Object>> items = hasMore ? rows.subList(0, size) : rows;
    return ownerPage(items, hasMore, page, size);
  }

  private Map<String, Object> ownerPage(
      List<Map<String, Object>> items, boolean hasMore, int page, int size) {
    return Map.of("items", items, "hasMore", hasMore, "page", page, "size", size);
  }

  private String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  public Map<String, Object> warnings(Actor actor, UUID id) {
    detail(actor, id);
    return Map.of(
        "scheduledTraining",
        db.queryForObject(
            "SELECT count(*) FROM training_schedules WHERE horse_id=? AND status IN"
                + " ('Scheduled','InProgress')",
            Long.class,
            id.toString()),
        "medicalRecords",
        db.queryForObject(
            "SELECT count(*) FROM medical_records WHERE horse_id=?", Long.class, id.toString()),
        "activePrescriptions",
        db.queryForObject(
            "SELECT count(*) FROM prescriptions WHERE horse_id=? AND status='Active'",
            Long.class,
            id.toString()));
  }

  @Transactional
  public Map<String, Object> delete(Actor actor, UUID id, boolean confirmed) {
    if (!confirmed) throw new ResponseStatusException(BAD_REQUEST, "Cần xác nhận xóa hồ sơ");
    String horseId = id.toString();
    Horse horse =
        horseRepository
            .findActiveByIdForUpdate(horseId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa"));
    long scheduled =
        db.queryForObject(
            "SELECT count(*) FROM training_schedules WHERE horse_id=? AND status IN"
                + " ('Scheduled','InProgress','Blocked')",
            Long.class,
            horseId);
    simulationRecorder.interruptHorse(horseId, "Horse profile deleted");
    db.update(
        "UPDATE training_schedules SET status='Cancelled' WHERE horse_id=? AND status IN"
            + " ('Scheduled','InProgress','Blocked')",
        horseId);
    db.update(
        "UPDATE daily_task_logs SET status='Cancelled' WHERE horse_id=? AND status='Pending'",
        horseId);
    db.update(
        "UPDATE periodic_care_schedules SET calendar_event_id=NULL WHERE horse_id=?", horseId);
    db.update(
        "UPDATE calendar_events SET status='Cancelled' WHERE horse_id=? AND status IN"
            + " ('Scheduled','InProgress','Blocked')",
        horseId);
    LocalDateTime deletedAt = time.utcNow();
    List<HorseRegistrationRequest> affectedRequests =
        registrationRequestRepository.findByApprovedHorseIdAndStatusIn(
            horseId, List.of("PENDING", "NEEDS_INFORMATION", "AWAITING_OWNER_CONSENT"));
    for (HorseRegistrationRequest req : affectedRequests) {
      req.setStatus("REJECTED");
      req.setReviewNote("Hồ sơ ngựa đã bị xóa");
      req.setReviewedBy(actor.id());
      req.setReviewedAt(deletedAt);
      HorseRegistrationService.appendHistoryEntry(
          req, "REJECTED", actor.id(), "Hồ sơ ngựa đã bị xóa", deletedAt, objectMapper);
      if (req.getOwnerId() != null) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID().toString());
        notification.setUserId(req.getOwnerId());
        notification.setRelatedTable("horse_registration_requests");
        notification.setRelatedId(req.getId());
        notification.setMessage(
            "Yêu cầu liên kết hồ sơ ngựa "
                + horse.getHorseName()
                + " đã bị đóng do hồ sơ ngựa đã bị xóa.");
        notification.setRead(false);
        notification.setCreatedAt(deletedAt);
        notificationRepository.save(notification);
      }
    }
    if (!affectedRequests.isEmpty()) {
      registrationRequestRepository.saveAllAndFlush(affectedRequests);
    }
    horse.setDeletedAt(deletedAt);
    horseRepository.saveAndFlush(horse);
    audit(actor.id(), "DELETE_HORSE: " + id);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("deleted_at", deletedAt);
    if (scheduled > 0) result.put("warning", "Ngựa đang có " + scheduled + " lịch tập sắp tới");
    return result;
  }

  private void audit(long actorId, String action) {
    invalidateCache();
    AuditLog log = new AuditLog();
    log.setId(UUID.randomUUID().toString());
    log.setUserId(actorId);
    log.setActionPerformed(action);
    log.setCreatedAt(time.utcNow());
    auditLogRepository.saveAndFlush(log);
  }
}
