package com.example.springbootbackend.service;

import com.example.springbootbackend.dto.request.HorseRequest;
import com.example.springbootbackend.dto.request.HorseUpdateRequest;
import com.example.springbootbackend.entity.AppUser;
import com.example.springbootbackend.entity.AuditLog;
import com.example.springbootbackend.entity.Horse;
import com.example.springbootbackend.entity.UserStatus;
import com.example.springbootbackend.repository.AppUserRepository;
import com.example.springbootbackend.repository.AuditLogRepository;
import com.example.springbootbackend.repository.HorseRegistrationRequestRepository;
import com.example.springbootbackend.repository.HorseRepository;
import com.example.springbootbackend.repository.StableBoxRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class HorseService {
    private final VetData db;
    private final VetTime time;
    private final int groomAssignmentWindowDays;
    private final AuditLogRepository auditLogRepository;
    private final HorseRepository horseRepository;
    private final HorseRegistrationRequestRepository registrationRequestRepository;
    private final AppUserRepository userRepository;
    private final StableBoxRepository stableBoxRepository;
    private final TrainingSimulationRecorder simulationRecorder;
    private final ObjectMapper objectMapper;

    public HorseService(VetData db, VetTime time,
            @Value("${app.groom.assignment-window-days:14}") int groomAssignmentWindowDays,
            AuditLogRepository auditLogRepository,
            HorseRepository horseRepository,
            HorseRegistrationRequestRepository registrationRequestRepository,
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
        this.userRepository = userRepository;
        this.stableBoxRepository = stableBoxRepository;
        this.simulationRecorder = simulationRecorder;
        this.objectMapper = objectMapper;
    }

    public record Actor(long id, String role) {
    }

    public Actor actor(String email, boolean write) {
        var rows = db.queryForList("SELECT u.user_id,u.status,u.deleted_at,u.must_change_password,r.role_name "
                + "FROM users u JOIN roles r ON r.role_id=u.role_id WHERE lower(u.email)=lower(?)", email);
        if (rows.isEmpty())
            throw new ResponseStatusException(FORBIDDEN, "Tài khoản chưa được duyệt");
        var user = rows.getFirst();
        if (!"APPROVED".equals(user.get("status")) || user.get("deleted_at") != null)
            throw new ResponseStatusException(FORBIDDEN, "Tài khoản chưa được duyệt");
        Actor actor = new Actor(((Number) user.get("user_id")).longValue(), (String) user.get("role_name"));
        if (Boolean.TRUE.equals(user.get("must_change_password")))
            throw new ResponseStatusException(FORBIDDEN, "Change password before using business APIs");
        if (write ? !actor.role().equals("CLUB_MANAGER")
                : !Set.of("CLUB_MANAGER", "HEAD_TRAINER", "VETERINARIAN", "HEAD_VETERINARIAN", "GROOM", "HORSE_OWNER")
                        .contains(actor.role()))
            throw new ResponseStatusException(FORBIDDEN, "Không có quyền truy cập");
        return actor;
    }

    public Actor ownerLookupActor(String email) {
        Actor actor = actor(email, false);
        if (!Set.of("CLUB_MANAGER", "HEAD_TRAINER").contains(actor.role()))
            throw new ResponseStatusException(FORBIDDEN, "Không có quyền truy cập");
        return actor;
    }

    public Map<String, Object> list(Actor actor, String search, String breed, String status, UUID stable,
            Boolean locked, int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new ResponseStatusException(BAD_REQUEST, "Phân trang không hợp lệ");
        if (actor.role().equals("HORSE_OWNER")
                && (search != null || breed != null || status != null || stable != null || locked != null))
            throw new ResponseStatusException(FORBIDDEN, "Bộ lọc chỉ dành cho quản lý và huấn luyện viên");
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
                    " AND (EXISTS (SELECT 1 FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                            + "WHERE ts.horse_id=h.id AND ts.assigned_groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?) "
                            + "OR EXISTS (SELECT 1 FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                            + "WHERE t.horse_id=h.id AND t.groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?))");
            args.add(actor.id());
            args.add(from);
            args.add(through);
            args.add(actor.id());
            args.add(from);
            args.add(through);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND lower(h.horse_name) LIKE ? ESCAPE '!'");
            args.add("%"
                    + search.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
                    + "%");
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
        long total = db.queryForObject("SELECT count(*) FROM horses h" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        String latestMetricWeight = "CASE WHEN h.current_weight_kg IS NOT NULL THEN h.current_weight_kg ELSE (SELECT m.body_weight_kg FROM shared_training_metrics m WHERE m.horse_id=h.id "
                + "AND m.body_weight_kg IS NOT NULL ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1) END AS latest_metric_weight_kg";
        String columns = actor.role().equals("GROOM")
                ? "h.id,h.horse_name,h.image_url,h.stable_box_id,h.current_status,h.height_cm,h.current_weight_kg,s.box_code,s.section,"
                        + latestMetricWeight
                : "h.*,s.box_code,s.section,u.full_name AS owner_name," + latestMetricWeight;
        var items = db.queryForList("SELECT " + columns
                + " FROM horses h LEFT JOIN stable_boxes s ON s.id=h.stable_box_id LEFT JOIN users u ON u.user_id=h.owner_id"
                + where + " ORDER BY h.created_at DESC,h.id LIMIT ? OFFSET ?", args.toArray());
        items = items.stream().map(row -> {
            Object latestWeight = row.remove("latest_metric_weight_kg");
            if (row.get("current_weight_kg") == null && latestWeight != null)
                row.put("current_weight_kg", latestWeight);
            return actor.role().equals("GROOM") ? groomHorseSummary(row) : row;
        }).toList();
        return Map.of("items", items, "data", items, "total", total, "page", page, "size", size, "limit", size);
    }

    public Map<String, Object> detail(Actor actor, UUID id) {
        if (actor.role().equals("GROOM")) {
            if (!groomHasAssignment(actor.id(), id.toString()))
                throw new ResponseStatusException(FORBIDDEN, "Horse is not assigned to this Groom");
            var assigned = db.queryForList(
                    "SELECT h.id,h.horse_name,h.image_url,h.stable_box_id,h.current_status,h.height_cm,h.current_weight_kg,"
                            + "s.box_code,s.section,CASE WHEN h.current_weight_kg IS NOT NULL THEN h.current_weight_kg ELSE (SELECT m.body_weight_kg FROM shared_training_metrics m WHERE m.horse_id=h.id AND m.body_weight_kg IS NOT NULL "
                            + "ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1) END AS latest_metric_weight_kg FROM horses h LEFT JOIN stable_boxes s ON s.id=h.stable_box_id "
                            + "WHERE h.id=? AND h.deleted_at IS NULL",
                    id.toString());
            if (assigned.isEmpty())
                throw new ResponseStatusException(FORBIDDEN, "Horse is not assigned to this Groom");
            return groomHorseSummary(assigned.getFirst());
        }
        var rows = db.queryForList("SELECT h.*,s.box_code,s.section,u.full_name AS owner_name,"
                + "CASE WHEN h.current_weight_kg IS NOT NULL THEN h.current_weight_kg ELSE (SELECT m.body_weight_kg FROM shared_training_metrics m WHERE m.horse_id=h.id AND m.body_weight_kg IS NOT NULL "
                + "ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1) END AS latest_metric_weight_kg "
                + "FROM horses h LEFT JOIN stable_boxes s ON s.id=h.stable_box_id LEFT JOIN users u ON u.user_id=h.owner_id "
                + "WHERE h.id=? AND h.deleted_at IS NULL", id.toString());
        if (rows.isEmpty())
            throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa");
        var horse = rows.getFirst();
        Object latestWeight = horse.remove("latest_metric_weight_kg");
        if (horse.get("current_weight_kg") == null && latestWeight != null)
            horse.put("current_weight_kg", latestWeight);
        if (actor.role().equals("HORSE_OWNER")
                && (!(horse.get("owner_id") instanceof Number owner) || owner.longValue() != actor.id()))
            throw new ResponseStatusException(FORBIDDEN, "Ngựa không thuộc sở hữu của bạn");
        horse.put("stable_box", Map.of("id", horse.get("stable_box_id"), "box_code", horse.get("box_code")));
        horse.put("owner", horse.get("owner_id") == null ? null
                : Map.of("id", horse.get("owner_id"), "full_name", horse.get("owner_name")));
        return horse;
    }

    public List<Map<String, Object>> approvedRegistrationDocuments(Actor actor, UUID horseId) {
        if (!Set.of("CLUB_MANAGER", "HORSE_OWNER").contains(actor.role()))
            throw new ResponseStatusException(FORBIDDEN, "Không có quyền xem giấy tờ sở hữu");
        detail(actor, horseId);
        return approvedRegistrationDocumentsForVerifiedHorse(actor, horseId);
    }

    public List<Map<String, Object>> approvedRegistrationDocumentsForVerifiedHorse(Actor actor, UUID horseId) {
        if (!Set.of("CLUB_MANAGER", "HORSE_OWNER").contains(actor.role()))
            throw new ResponseStatusException(FORBIDDEN, "Không có quyền xem giấy tờ sở hữu");
        String ownerFilter = actor.role().equals("HORSE_OWNER") ? " AND r.owner_id=?" : "";
        List<Object> args = new ArrayList<>();
        args.add(horseId.toString());
        if (actor.role().equals("HORSE_OWNER"))
            args.add(actor.id());
        return db.queryForList(
                "SELECT d.id,d.original_filename AS name,d.content_type,d.file_size,d.document_type,d.revision_no,d.uploaded_at,d.object_path "
                        + "FROM horse_registration_documents d JOIN horse_registration_requests r ON r.id=d.request_id "
                        + "WHERE r.approved_horse_id=? AND r.status='APPROVED'" + ownerFilter
                        + " ORDER BY d.uploaded_at DESC",
                args.toArray());
    }

    private boolean groomHasAssignment(long groomId, String horseId) {
        LocalDate today = time.today();
        LocalDate from = today.minusDays(groomAssignmentWindowDays);
        LocalDate through = today.plusDays(groomAssignmentWindowDays);
        Long count = db.queryForObject("SELECT count(*) FROM horses h WHERE h.id=? AND h.deleted_at IS NULL AND ("
                + "EXISTS (SELECT 1 FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                + "WHERE ts.horse_id=h.id AND ts.assigned_groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?) OR "
                + "EXISTS (SELECT 1 FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                + "WHERE t.horse_id=h.id AND t.groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?))",
                Long.class, horseId, groomId, from, through, groomId, from, through);
        return count != null && count > 0;
    }

    private Map<String, Object> groomHorseSummary(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("horse_name", row.get("horse_name"));
        result.put("image_url", row.get("image_url"));
        if (row.get("stable_box_id") == null)
            result.put("stable_box", null);
        else {
            Map<String, Object> stable = new LinkedHashMap<>();
            stable.put("box_code", row.get("box_code"));
            stable.put("section", row.get("section"));
            result.put("stable_box", stable);
        }
        result.put("current_status", row.get("current_status"));
        result.put("height_cm", row.get("height_cm"));
        result.put("current_weight_kg", row.get("current_weight_kg") == null ? row.get("latest_metric_weight_kg")
                : row.get("current_weight_kg"));
        return result;
    }

    @Transactional
    public Map<String, Object> save(Actor actor, UUID id, HorseRequest request) {
        if (id == null) {
            throw new ResponseStatusException(FORBIDDEN,
                    "Hồ sơ ngựa mới phải do Horse Owner gửi yêu cầu và được Club Manager duyệt.");
        }
        Horse horse = horseRepository.findActiveByIdForUpdate(id.toString())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa"));
        if (!Objects.equals(horse.getOwnerId(), request.ownerId()))
            throw new ResponseStatusException(CONFLICT,
                    "Thay đổi chủ sở hữu cần yêu cầu liên kết và sự đồng ý của chủ hiện tại.");
        if (request.birthYear() != null && request.birthYear() > time.today().getYear())
            throw new ResponseStatusException(BAD_REQUEST, "Năm sinh không được ở tương lai");
        if (request.ownerId() != null) {
            AppUser owner = userRepository.findById(request.ownerId()).orElse(null);
            if (owner == null || owner.getStatus() != UserStatus.APPROVED || owner.getDeletedAt() != null
                    || owner.isMustChangePassword() || owner.getRole() == null
                    || !"HORSE_OWNER".equals(owner.getRole().getName()))
                throw new ResponseStatusException(BAD_REQUEST, "Chủ ngựa phải là Horse Owner đã được duyệt");
        }
        // Serialize competing admissions to the same stable; never rely on a dropdown
        // alone.
        var box = stableBoxRepository.findByIdForUpdate(request.stableBoxId().toString())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Chuồng không tồn tại"));
        long occupied = horseRepository.countByStableBoxIdAndDeletedAtIsNullAndIdNot(
                request.stableBoxId().toString(), id.toString());
        if (!"Available".equals(box.getStatus()) || box.getCapacity() == null || occupied >= box.getCapacity())
            throw new ResponseStatusException(CONFLICT, "Chuồng đã đầy hoặc không khả dụng");
        // Chuồng cách ly / chấn thương chỉ do bác sĩ thú y điều chuyển qua cập nhật trạng thái sức khỏe.
        boolean alreadyInThisBox = request.stableBoxId().toString().equals(horse.getStableBoxId());
        String boxType = db.queryForObject("SELECT COALESCE(box_type,'Normal') FROM stable_boxes WHERE id=?",
                String.class, request.stableBoxId().toString());
        if (!alreadyInThisBox && !"Normal".equals(boxType))
            throw new ResponseStatusException(BAD_REQUEST, "Chuồng cách ly/chấn thương chỉ được điều chuyển bởi bác sĩ thú y");
        String microchip = optionalIdentifier(request.microchipNumber());
        String passport = optionalIdentifier(request.passportNumber());
        validateIdentityAvailable(microchip, passport, id.toString(), id.toString());
        String path = request.imagePath();
        if (path == null)
            path = horse.getImageUrl();
        if (path != null && !Objects.equals(path, horse.getImageUrl())) {
            Long uploaded = db.queryForObject(
                    "SELECT count(*) FROM audit_logs WHERE user_id=? AND action_performed=?",
                    Long.class, actor.id(), "UPLOAD_HORSE_IMAGE:" + path);
            if (uploaded == null || uploaded == 0)
                throw new ResponseStatusException(BAD_REQUEST,
                        "Ảnh phải được upload qua hệ thống bởi tài khoản hiện tại");
        }
        horse.setHorseName(request.horseName().trim());
        horse.setBreed(request.breed());
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
        if (!actor.role().equals("CLUB_MANAGER"))
            throw new ResponseStatusException(FORBIDDEN, "Chỉ Club Manager được xem lịch sử sở hữu");
        Horse horse = horseRepository.findByIdAndDeletedAtIsNull(horseId.toString())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa"));
        String json = horse.getOwnershipHistoryJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> entries = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = entries.size() - 1; i >= 0; i--) {
                Map<String, Object> item = new LinkedHashMap<>(entries.get(i));
                item.put("previous_owner_name", findUserName(item.get("previous_owner_id")));
                item.put("new_owner_name", findUserName(item.get("new_owner_id")));
                item.put("changed_by_name", findUserName(item.get("changed_by")));
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String findUserName(Object userIdObj) {
        if (!(userIdObj instanceof Number num)) return null;
        return userRepository.findById(num.longValue()).map(AppUser::getFullName).orElse(null);
    }

    private void ownershipChange(String horseId, Long previousOwner, Long newOwner, long changedBy, String reason) {
        Horse horse = horseRepository.findById(horseId).orElse(null);
        if (horse == null) return;
        try {
            List<Map<String, Object>> entries = new ArrayList<>();
            if (horse.getOwnershipHistoryJson() != null && !horse.getOwnershipHistoryJson().isBlank()) {
                entries.addAll(objectMapper.readValue(horse.getOwnershipHistoryJson(), new TypeReference<List<Map<String, Object>>>() {}));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("horse_id", horseId);
            entry.put("previous_owner_id", previousOwner);
            entry.put("new_owner_id", newOwner);
            entry.put("changed_by", changedBy);
            entry.put("change_reason", reason);
            entry.put("changed_at", time.utcNow().toString());
            entries.add(entry);
            horse.setOwnershipHistoryJson(objectMapper.writeValueAsString(entries));
            horseRepository.saveAndFlush(horse);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize ownership history", e);
        }
    }

    private void validateIdentityAvailable(String microchip, String passport, String horseId, String requestId) {
        List<String> activeRequests = List.of("PENDING", "NEEDS_INFORMATION", "AWAITING_OWNER_CONSENT");
        if (microchip != null && (horseRepository.existsByMicrochipNumberIgnoreCaseAndIdNot(microchip, horseId)
                || registrationRequestRepository.existsOtherActiveMicrochip("REGISTER", activeRequests,
                        microchip, requestId)))
            throw new ResponseStatusException(CONFLICT, "Số microchip đã được dùng cho một hồ sơ khác");
        if (passport != null && (horseRepository.existsByPassportNumberIgnoreCaseAndIdNot(passport, horseId)
                || registrationRequestRepository.existsOtherActivePassport("REGISTER", activeRequests,
                        passport, requestId)))
            throw new ResponseStatusException(CONFLICT, "Số hộ chiếu đã được dùng cho một hồ sơ khác");
    }

    private static String optionalIdentifier(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Transactional
    public Map<String, Object> update(Actor actor, UUID id, HorseUpdateRequest request) {
        String horseName = request.horseName().trim();
        String breed = request.breed();
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
        var merged = new HorseRequest(horseName, breed, birthYear, heightCm, currentWeightKg, father, mother,
                microchip, passport, image, UUID.fromString(stable), owner, request.confirmOwnerChange());
        return save(actor, id, merged);
    }

    public List<Map<String, Object>> stables() {
        return db.queryForList(
                "SELECT s.*, (SELECT count(*) FROM horses h WHERE h.stable_box_id=s.id AND h.deleted_at IS NULL) AS occupied FROM stable_boxes s WHERE s.status='Available' AND COALESCE(s.box_type,'Normal')='Normal' AND s.capacity > (SELECT count(*) FROM horses h WHERE h.stable_box_id=s.id AND h.deleted_at IS NULL) ORDER BY s.box_code");
    }

    public Map<String, Object> owners(String search, int page, int size) {
        if (page < 0 || size < 1 || size > 50)
            throw new ResponseStatusException(BAD_REQUEST, "Phân trang chủ sở hữu không hợp lệ");
        if (search == null || search.isBlank())
            return ownerPage(List.of(), false, page, size);

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
        if (term.length() < 3 && !searchByIdOnly)
            return ownerPage(List.of(), false, page, size);

        String sql = "SELECT u.user_id,u.full_name,u.email,u.phone "
                + "FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE r.role_name='HORSE_OWNER' AND u.status='APPROVED' AND u.deleted_at IS NULL AND ";
        List<Object> args = new ArrayList<>();
        if (searchByIdOnly) {
            sql += "u.user_id=? ";
            args.add(exactId);
        } else {
            String textTerm = exactId == null ? term : idTerm;
            String pattern = "%" + escapeLike(textTerm.toLowerCase(Locale.ROOT)) + "%";
            sql += "(lower(u.full_name) LIKE ? ESCAPE '!' "
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

    private Map<String, Object> ownerPage(List<Map<String, Object>> items, boolean hasMore, int page, int size) {
        return Map.of("items", items, "hasMore", hasMore, "page", page, "size", size);
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public Map<String, Object> warnings(Actor actor, UUID id) {
        detail(actor, id);
        return Map.of("scheduledTraining", db.queryForObject(
                "SELECT count(*) FROM training_schedules WHERE horse_id=? AND status IN ('Scheduled','InProgress')",
                Long.class, id.toString()),
                "medicalRecords",
                db.queryForObject("SELECT count(*) FROM medical_records WHERE horse_id=?", Long.class, id.toString()),
                "activePrescriptions",
                db.queryForObject("SELECT count(*) FROM prescriptions WHERE horse_id=? AND status='Active'", Long.class,
                        id.toString()));
    }

    @Transactional
    public Map<String, Object> delete(Actor actor, UUID id, boolean confirmed) {
        if (!confirmed)
            throw new ResponseStatusException(BAD_REQUEST, "Cần xác nhận xóa hồ sơ");
        String horseId = id.toString();
        Horse horse = horseRepository.findActiveByIdForUpdate(horseId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa"));
        long scheduled = db.queryForObject(
                "SELECT count(*) FROM training_schedules WHERE horse_id=? AND status IN ('Scheduled','InProgress','Blocked')",
                Long.class, horseId);
        simulationRecorder.interruptHorse(horseId, "Horse profile deleted");
        db.update("UPDATE training_schedules SET status='Cancelled' WHERE horse_id=? AND status IN ('Scheduled','InProgress','Blocked')", horseId);
        db.update("UPDATE daily_task_logs SET status='Cancelled' WHERE horse_id=? AND status='Pending'", horseId);
        db.update("UPDATE periodic_care_schedules SET calendar_event_id=NULL WHERE horse_id=?", horseId);
        db.update("UPDATE calendar_events SET status='Cancelled' WHERE horse_id=? AND status IN ('Scheduled','InProgress','Blocked')", horseId);
        LocalDateTime deletedAt = time.utcNow();
        db.update("UPDATE horse_registration_requests SET status='REJECTED', review_note='Hồ sơ ngựa đã bị xóa', reviewed_by=?, reviewed_at=? "
                + "WHERE approved_horse_id=? AND status IN ('PENDING','NEEDS_INFORMATION','AWAITING_OWNER_CONSENT')",
                actor.id(), deletedAt, horseId);
        horse.setDeletedAt(deletedAt);
        horseRepository.saveAndFlush(horse);
        audit(actor.id(), "DELETE_HORSE: " + id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("deleted_at", deletedAt);
        if (scheduled > 0)
            result.put("warning", "Ngựa đang có " + scheduled + " lịch tập sắp tới");
        return result;
    }

    private void audit(long actorId, String action) {
        AuditLog log = new AuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setUserId(actorId);
        log.setActionPerformed(action);
        log.setCreatedAt(time.utcNow());
        auditLogRepository.saveAndFlush(log);
    }
}
