package com.example.springbootbackend.horse.service;

import com.example.springbootbackend.horse.storage.HorseStorage;
import com.example.springbootbackend.veterinarian.support.VetTime;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class HorseRegistrationService {
    private final JdbcTemplate db;
    private final HorseStorage storage;
    private final VetTime time;

    public HorseRegistrationService(JdbcTemplate db, HorseStorage storage, VetTime time) {
        this.db = db;
        this.storage = storage;
        this.time = time;
    }

    @Transactional
    public Map<String, Object> submit(String email, JsonNode body, MultipartFile horseImage, List<MultipartFile> files) {
        long ownerId = requireUser(email, "HORSE_OWNER");
        String requestType = requiredText(body, "requestType", 20).toUpperCase(Locale.ROOT);
        if (!Set.of("REGISTER", "LINK").contains(requestType))
            throw new ResponseStatusException(BAD_REQUEST, "requestType chỉ nhận REGISTER hoặc LINK");
        String horseName = requiredText(body, "horseName", 100);
        if (horseName.length() < 2) throw new ResponseStatusException(BAD_REQUEST, "Tên ngựa phải có ít nhất 2 ký tự");
        if (files == null || files.isEmpty() || files.size() > 5)
            throw new ResponseStatusException(BAD_REQUEST, "Đính kèm từ 1 đến 5 giấy tờ xác minh");

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Tệp giấy tờ không được để trống");
            if (file.getSize() > 10 * 1024 * 1024) throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "Mỗi giấy tờ không được vượt quá 10 MB");
        }

        Integer birthYear = optionalInt(body, "birthYear");
        if (birthYear != null && (birthYear < 1900 || birthYear > time.today().getYear()))
            throw new ResponseStatusException(BAD_REQUEST, "Năm sinh không hợp lệ");
        BigDecimal height = optionalDecimal(body, "heightCm", new BigDecimal("50"), new BigDecimal("250"), 1);
        BigDecimal weight = optionalDecimal(body, "currentWeightKg", new BigDecimal("100"), new BigDecimal("900"), 2);
        String breed = optionalText(body, "breed", 50);
        String father = optionalText(body, "pedigreeFather", 100);
        String mother = optionalText(body, "pedigreeMother", 100);
        String chip = optionalText(body, "microchipNumber", 100);
        String passport = optionalText(body, "passportNumber", 100);
        String ownerNote = optionalText(body, "ownerNote", 1000);

        String id = UUID.randomUUID().toString();
        String horseImagePath = storage.uploadRegistrationHorseImage(horseImage, ownerId);
        db.update("INSERT INTO horse_registration_requests(id,owner_id,request_type,horse_name,breed,birth_year,height_cm,current_weight_kg,pedigree_father,pedigree_mother,microchip_number,passport_number,owner_note,horse_image_path,status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?)",
                id, ownerId, requestType, horseName, breed, birthYear, height, weight, father, mother, chip, passport, ownerNote, horseImagePath, time.utcNow());
        List<String> documentPaths = parallelMap(files, storage::uploadRegistrationDocument, "Không tải lên được giấy tờ xác minh");
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            if (file == null || file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Giấy tờ không được rỗng");
            String path = documentPaths.get(index);
            db.update("INSERT INTO horse_registration_documents(id,request_id,object_path,original_filename,content_type,file_size,uploaded_at) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), id, path, safeFilename(file.getOriginalFilename()), file.getContentType(), file.getSize(), time.utcNow());
        }
        return ownerRequest(id, ownerId);
    }

    public List<Map<String, Object>> ownerRequests(String email) {
        long ownerId = requireUser(email, "HORSE_OWNER");
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM horse_registration_requests WHERE owner_id=? ORDER BY created_at DESC", ownerId);
        return rows.stream().map(row -> attachDocuments(row)).toList();
    }

    public List<Map<String, Object>> pendingRequests(String email) {
        requireUser(email, "CLUB_MANAGER");
        List<Map<String, Object>> rows = db.queryForList("SELECT r.*,u.full_name AS owner_name,u.email AS owner_email,u.phone AS owner_phone FROM horse_registration_requests r JOIN users u ON u.user_id=r.owner_id WHERE r.status='PENDING' ORDER BY r.created_at ASC");
        return rows.stream().map(row -> attachDocuments(row)).toList();
    }

    @Transactional
    public Map<String, Object> review(String managerEmail, String requestId, JsonNode body, String ip) {
        long managerId = requireUser(managerEmail, "CLUB_MANAGER");
        UUID id = parseUuid(requestId, "Mã yêu cầu không hợp lệ");
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM horse_registration_requests WHERE id=? FOR UPDATE", id.toString());
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu hồ sơ ngựa");
        Map<String, Object> request = rows.getFirst();
        if (!"PENDING".equals(request.get("status"))) throw new ResponseStatusException(CONFLICT, "Yêu cầu đã được xử lý");
        String action = requiredText(body, "action", 10).toUpperCase(Locale.ROOT);
        String note = optionalText(body, "reviewNote", 1000);
        if ("REJECT".equals(action)) {
            if (!StringUtils.hasText(note))
                throw new ResponseStatusException(BAD_REQUEST, "Vui lòng nhập lý do trước khi từ chối yêu cầu.");
            db.update("UPDATE horse_registration_requests SET status='REJECTED',reviewed_by=?,reviewed_at=?,review_note=? WHERE id=? AND status='PENDING'",
                    managerId, time.utcNow(), note, id.toString());
            notifyOwner(((Number) request.get("owner_id")).longValue(), id.toString(), "Yêu cầu hồ sơ ngựa " + request.get("horse_name") + " chưa được duyệt." + (note == null ? "" : " Lý do: " + note));
            audit(managerId, "REJECT_HORSE_REGISTRATION: " + id, ip);
            return ownerRequest(id.toString(), ((Number) request.get("owner_id")).longValue());
        }
        if (!"APPROVE".equals(action)) throw new ResponseStatusException(BAD_REQUEST, "action chỉ nhận APPROVE hoặc REJECT");

        long ownerId = ((Number) request.get("owner_id")).longValue();
        String horseId;
        if ("REGISTER".equals(request.get("request_type"))) {
            UUID stableId = parseUuid(requiredText(body, "stableBoxId", 36), "Cần chọn chuồng cho ngựa mới");
            validateStable(stableId);
            horseId = createHorseFromRequest(request, stableId, ownerId, managerId);
        } else {
            UUID targetId = parseUuid(requiredText(body, "horseId", 36), "Cần chọn hồ sơ ngựa cần liên kết");
            horseId = linkHorse(targetId, ownerId, managerId, Objects.toString(request.get("horse_image_path"), null));
        }
        db.update("UPDATE horse_registration_requests SET status='APPROVED',approved_horse_id=?,reviewed_by=?,reviewed_at=?,review_note=? WHERE id=? AND status='PENDING'",
                horseId, managerId, time.utcNow(), note, id.toString());
        notifyOwner(ownerId, id.toString(), "Yêu cầu hồ sơ ngựa " + request.get("horse_name") + " đã được duyệt.");
        audit(managerId, "APPROVE_HORSE_REGISTRATION: " + id + " -> " + horseId, ip);
        return ownerRequest(id.toString(), ownerId);
    }

    private String createHorseFromRequest(Map<String, Object> request, UUID stableId, long ownerId, long managerId) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO horses(id,horse_name,breed,birth_year,height_cm,current_weight_kg,pedigree_father,pedigree_mother,image_url,stable_box_id,owner_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, request.get("horse_name"), request.get("breed"), request.get("birth_year"), request.get("height_cm"), request.get("current_weight_kg"), request.get("pedigree_father"), request.get("pedigree_mother"), request.get("horse_image_path"), stableId.toString(), ownerId, time.utcNow());
        ownershipChange(id, null, ownerId, managerId, "REGISTRATION_APPROVAL");
        return id;
    }

    private String linkHorse(UUID horseId, long ownerId, long managerId, String submittedImagePath) {
        List<Map<String, Object>> rows = db.queryForList("SELECT id,owner_id,image_url FROM horses WHERE id=? AND deleted_at IS NULL FOR UPDATE", horseId.toString());
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy hồ sơ ngựa cần liên kết");
        Object current = rows.getFirst().get("owner_id");
        Long previousOwner = current instanceof Number number ? number.longValue() : null;
        if (!Objects.equals(previousOwner, ownerId)) {
            db.update("UPDATE horses SET owner_id=? WHERE id=?", ownerId, horseId.toString());
            ownershipChange(horseId.toString(), previousOwner, ownerId, managerId,
                    previousOwner == null ? "LINK_APPROVAL" : "OWNER_CHANGE_APPROVAL");
        }
        if (rows.getFirst().get("image_url") == null && submittedImagePath != null)
            db.update("UPDATE horses SET image_url=? WHERE id=?", submittedImagePath, horseId.toString());
        return horseId.toString();
    }

    private void validateStable(UUID id) {
        List<Map<String, Object>> boxes = db.queryForList("SELECT * FROM stable_boxes WHERE id=? FOR UPDATE", id.toString());
        if (boxes.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Chuồng không tồn tại");
        Map<String, Object> box = boxes.getFirst();
        long occupied = db.queryForObject("SELECT count(*) FROM horses WHERE stable_box_id=? AND deleted_at IS NULL", Long.class, id.toString());
        if (!"Available".equals(box.get("status")) || !(box.get("capacity") instanceof Number capacity) || occupied >= capacity.intValue())
            throw new ResponseStatusException(CONFLICT, "Chuồng đã đầy hoặc không khả dụng");
    }

    private void ownershipChange(String horseId, Long previousOwner, Long newOwner, long managerId, String reason) {
        db.update("INSERT INTO horse_ownership_history(id,horse_id,previous_owner_id,new_owner_id,changed_by,change_reason,changed_at) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), horseId, previousOwner, newOwner, managerId, reason, time.utcNow());
    }

    private Map<String, Object> ownerRequest(String id, long ownerId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM horse_registration_requests WHERE id=? AND owner_id=?", id, ownerId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu");
        return attachDocuments(rows.getFirst());
    }

    private Map<String, Object> attachDocuments(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        String imagePath = Objects.toString(row.get("horse_image_path"), null);
        List<Map<String, Object>> docs = db.query("SELECT id,object_path,original_filename,content_type,file_size,uploaded_at FROM horse_registration_documents WHERE request_id=? ORDER BY uploaded_at",
                (rs, index) -> {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    String path = rs.getString("object_path");
                    doc.put("object_path", path);
                    doc.put("id", rs.getString("id"));
                    doc.put("name", rs.getString("original_filename"));
                    doc.put("content_type", rs.getString("content_type"));
                    doc.put("file_size", rs.getLong("file_size"));
                    return doc;
                }, row.get("id"));
        List<String> paths = new ArrayList<>();
        if (imagePath != null) paths.add(imagePath);
        docs.stream().map(doc -> Objects.toString(doc.get("object_path"), null)).filter(Objects::nonNull).forEach(paths::add);
        List<String> urls = parallelMap(paths, path -> Objects.toString(storage.signedUrl(path).get("url"), null), "Không tạo được liên kết giấy tờ xác minh");
        Map<String, String> signedUrls = new HashMap<>();
        for (int index = 0; index < paths.size(); index++) signedUrls.put(paths.get(index), urls.get(index));
        result.put("horse_image_url", imagePath == null ? null : signedUrls.get(imagePath));
        for (Map<String, Object> doc : docs) {
            String path = Objects.toString(doc.remove("object_path"), null);
            doc.put("url", signedUrls.get(path));
        }
        result.put("documents", docs);
        return result;
    }

    private static <T, R> List<R> parallelMap(List<T> values, Function<T, R> operation, String errorMessage) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<R>> futures = values.stream()
                    .map(value -> CompletableFuture.supplyAsync(() -> operation.apply(value), executor))
                    .toList();
            List<R> results = new ArrayList<>(Collections.nCopies(values.size(), null));
            RuntimeException failure = null;
            for (int index = 0; index < futures.size(); index++) {
                try {
                    results.set(index, futures.get(index).join());
                } catch (CompletionException exception) {
                    Throwable cause = exception.getCause();
                    if (failure == null) {
                        failure = cause instanceof RuntimeException runtimeException
                                ? runtimeException
                                : new IllegalStateException(errorMessage, cause);
                    }
                }
            }
            if (failure != null) throw failure;
            return results;
        }
    }

    private long requireUser(String email, String role) {
        List<Map<String, Object>> rows = db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id WHERE lower(u.email)=lower(?) AND r.role_name=? AND u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL", email, role);
        if (rows.isEmpty()) throw new ResponseStatusException(FORBIDDEN, "Tài khoản không có quyền thao tác hồ sơ ngựa");
        return ((Number) rows.getFirst().get("user_id")).longValue();
    }

    private void notifyOwner(long ownerId, String requestId, String message) {
        db.update("INSERT INTO notifications(id,user_id,related_table,related_id,message,is_read,created_at) VALUES (?,?,?,?,?,FALSE,?)",
                UUID.randomUUID().toString(), ownerId, "horse_registration_requests", requestId, message, time.utcNow());
    }

    private void audit(long managerId, String action, String ip) {
        db.update("INSERT INTO audit_logs(id,user_id,action_performed,ip_address,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), managerId, action, ip, time.utcNow());
    }

    private static String requiredText(JsonNode body, String key, int max) {
        String value = body == null ? "" : body.path(key).asText("").trim();
        if (!StringUtils.hasText(value) || value.length() > max) throw new ResponseStatusException(BAD_REQUEST, "Trường " + key + " không hợp lệ");
        return value;
    }

    private static String optionalText(JsonNode body, String key, int max) {
        String value = body == null ? "" : body.path(key).asText("").trim();
        if (value.isEmpty()) return null;
        if (value.length() > max) throw new ResponseStatusException(BAD_REQUEST, "Trường " + key + " vượt quá độ dài cho phép");
        return value;
    }

    private static Integer optionalInt(JsonNode body, String key) {
        JsonNode value = body == null ? null : body.get(key);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        if (!value.canConvertToInt()) throw new ResponseStatusException(BAD_REQUEST, "Trường " + key + " không hợp lệ");
        return value.asInt();
    }

    private static BigDecimal optionalDecimal(JsonNode body, String key, BigDecimal min, BigDecimal max, int maxScale) {
        JsonNode value = body == null ? null : body.get(key);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        try {
            BigDecimal number = new BigDecimal(value.asText());
            if (number.compareTo(min) < 0 || number.compareTo(max) > 0 || number.scale() > maxScale)
                throw new ResponseStatusException(BAD_REQUEST, "Trường " + key + " không hợp lệ");
            return number;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Trường " + key + " không hợp lệ");
        }
    }

    private static UUID parseUuid(String value, String message) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(BAD_REQUEST, message); }
    }

    private static String safeFilename(String original) {
        String value = Objects.toString(original, "giay-to").replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.isEmpty()) value = "giay-to";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }
}
