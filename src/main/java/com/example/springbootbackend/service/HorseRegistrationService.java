package com.example.springbootbackend.service;

import com.example.springbootbackend.entity.AppUser;
import com.example.springbootbackend.entity.AuditLog;
import com.example.springbootbackend.entity.Horse;
import com.example.springbootbackend.entity.HorseRegistrationDocument;
import com.example.springbootbackend.entity.HorseRegistrationRequest;
import com.example.springbootbackend.entity.Notification;
import com.example.springbootbackend.entity.UserStatus;
import com.example.springbootbackend.repository.AppUserRepository;
import com.example.springbootbackend.repository.AuditLogRepository;
import com.example.springbootbackend.repository.HorseRegistrationDocumentRepository;
import com.example.springbootbackend.repository.HorseRegistrationRequestRepository;
import com.example.springbootbackend.repository.HorseRepository;
import com.example.springbootbackend.repository.NotificationRepository;
import com.example.springbootbackend.repository.StableBoxRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import static org.springframework.http.HttpStatus.*;

@Service
public class HorseRegistrationService {
    private static final List<String> ACTIVE_REQUEST_STATUSES =
            List.of("PENDING", "NEEDS_INFORMATION", "AWAITING_OWNER_CONSENT");
    private static final Set<String> DOCUMENT_TYPES =
            Set.of("OWNERSHIP_PROOF", "HORSE_CERTIFICATE", "HEALTH_DOCUMENT", "OTHER");
    private static final Set<String> MANAGER_FILTER_STATUSES =
            Set.of("PENDING", "NEEDS_INFORMATION", "AWAITING_OWNER_CONSENT", "APPROVED", "REJECTED");
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("requestType", "Loại yêu cầu"),
            Map.entry("horseName", "Tên ngựa"),
            Map.entry("breed", "Giống"),
            Map.entry("birthYear", "Năm sinh"),
            Map.entry("heightCm", "Chiều cao (cm)"),
            Map.entry("currentWeightKg", "Cân nặng (kg)"),
            Map.entry("pedigreeFather", "Tên ngựa cha"),
            Map.entry("pedigreeMother", "Tên ngựa mẹ"),
            Map.entry("microchipNumber", "Số microchip"),
            Map.entry("passportNumber", "Số hộ chiếu ngựa"),
            Map.entry("ownerNote", "Ghi chú thêm"),
            Map.entry("action", "Thao tác duyệt"),
            Map.entry("reviewNote", "Ghi chú duyệt"),
            Map.entry("stableBoxId", "Chuồng trại"),
            Map.entry("horseId", "Hồ sơ ngựa"),
            Map.entry("note", "Ghi chú"));

    private final AppUserRepository userRepository;
    private final HorseRepository horseRepository;
    private final StableBoxRepository stableBoxRepository;
    private final HorseRegistrationRequestRepository requestRepository;
    private final HorseRegistrationDocumentRepository documentRepository;
    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;
    private final HorseStorage storage;
    private final VetTime time;
    private final ObjectMapper objectMapper;
    private final VetData db;
    private final TransactionTemplate tx;

    public HorseRegistrationService(AppUserRepository userRepository, HorseRepository horseRepository,
            StableBoxRepository stableBoxRepository, HorseRegistrationRequestRepository requestRepository,
            HorseRegistrationDocumentRepository documentRepository,
            NotificationRepository notificationRepository, AuditLogRepository auditLogRepository,
            HorseStorage storage, VetTime time, ObjectMapper objectMapper,
            VetData db, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.horseRepository = horseRepository;
        this.stableBoxRepository = stableBoxRepository;
        this.requestRepository = requestRepository;
        this.documentRepository = documentRepository;
        this.notificationRepository = notificationRepository;
        this.auditLogRepository = auditLogRepository;
        this.storage = storage;
        this.time = time;
        this.objectMapper = objectMapper;
        this.db = db;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> submit(String email, JsonNode body, MultipartFile horseImage,
            List<MultipartFile> files, JsonNode documentTypes, String ip) {
        AppUser owner = requireUser(email, "HORSE_OWNER");
        Submission submission = parseSubmission(body, null);
        List<MultipartFile> validFiles = validateFiles(files, documentTypes, true);
        List<String> types = parseDocumentTypes(documentTypes, validFiles);
        validateRequiredDocumentTypes(submission.requestType(), types);
        if ("REGISTER".equals(submission.requestType()))
            validateUniqueIdentifiers(submission.microchipNumber(), submission.passportNumber(), null, null);
        validateNoDuplicateActiveRequest(owner.getId(), submission, null);

        List<String> uploadedPaths = new CopyOnWriteArrayList<>();
        HorseRegistrationRequest saved;
        try {
            UploadedRegistrationAssets uploaded = uploadAssetsInParallel(horseImage, owner.getId(), validFiles, uploadedPaths);
            saved = tx.execute(status -> {
                AppUser txOwner = requireUser(email, "HORSE_OWNER");
                if ("REGISTER".equals(submission.requestType())) {
                    acquireIdentityLocks(db, submission.microchipNumber(), submission.passportNumber());
                    validateUniqueIdentifiers(submission.microchipNumber(), submission.passportNumber(), null, null);
                }
                validateNoDuplicateActiveRequest(txOwner.getId(), submission, null);

                HorseRegistrationRequest request = new HorseRegistrationRequest();
                applySubmission(request, submission);
                request.setId(UUID.randomUUID().toString());
                request.setOwnerId(txOwner.getId());
                request.setRequestType(submission.requestType());
                request.setHorseImagePath(uploaded.horseImagePath());
                request.setStatus("PENDING");
                request.setRevisionNo(1);
                request.setCreatedAt(time.utcNow());
                requestRepository.saveAndFlush(request);

                saveUploadedDocuments(request, validFiles, types, uploaded.documentPaths());
                recordHistory(request, "SUBMITTED", txOwner.getId(), null);
                if (uploaded.horseImagePath() != null) {
                    audit(txOwner.getId(), "UPLOAD_HORSE_IMAGE:" + uploaded.horseImagePath(), ip);
                }
                audit(txOwner.getId(), "SUBMIT_HORSE_REGISTRATION: " + request.getId(), ip);
                notifyManagers(request.getId(), "Horse Owner " + txOwner.getFullName()
                        + ("LINK".equals(request.getRequestType()) ? " vừa gửi yêu cầu liên kết hồ sơ ngựa " : " vừa gửi yêu cầu đăng ký ngựa mới ")
                        + request.getHorseName() + ".");
                return request;
            });
        } catch (RuntimeException exception) {
            uploadedPaths.forEach(storage::deleteQuietly);
            throw exception;
        }
        return ownerRequestSafe(saved, true);
    }

    public Map<String, Object> resubmit(String email, String requestId, JsonNode body,
            MultipartFile horseImage, List<MultipartFile> files, JsonNode documentTypes, String ip) {
        AppUser owner = requireUser(email, "HORSE_OWNER");
        UUID id = parseUuid(requestId, "Mã yêu cầu không hợp lệ");
        HorseRegistrationRequest preCheck = requestRepository
                .findByIdAndOwnerId(id.toString(), owner.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu hồ sơ ngựa"));
        if (!"NEEDS_INFORMATION".equals(preCheck.getStatus()))
            throw new ResponseStatusException(CONFLICT, "Chỉ yêu cầu cần bổ sung mới được chỉnh sửa và gửi lại");

        Submission submission = parseSubmission(body, preCheck.getRequestType());
        boolean removeHorseImage = body != null && body.path("removeHorseImage").asBoolean(false);
        Set<String> removeDocIds = parseRemoveDocumentIds(body);
        List<MultipartFile> validFiles = validateFiles(files, documentTypes, false);
        List<String> types = parseDocumentTypes(documentTypes, validFiles);
        MultipartFile nextImage = (horseImage != null && !horseImage.isEmpty()) ? horseImage : null;
        List<HorseRegistrationDocument> preExistingDocs = documentRepository
                .findByRequestIdOrderByUploadedAtAscIdAsc(preCheck.getId());
        validateResubmitDocumentsAndChanges(preCheck, submission, preExistingDocs, removeDocIds, types,
                nextImage != null, removeHorseImage);
        if ("REGISTER".equals(preCheck.getRequestType()))
            validateUniqueIdentifiers(submission.microchipNumber(), submission.passportNumber(),
                    preCheck.getId(), null);
        validateNoDuplicateActiveRequest(owner.getId(), submission, preCheck.getId());

        List<String> uploadedPaths = new CopyOnWriteArrayList<>();
        List<String> removedStoragePaths = new ArrayList<>();
        HorseRegistrationRequest saved;
        try {
            UploadedRegistrationAssets uploaded = uploadAssetsInParallel(nextImage, owner.getId(), validFiles, uploadedPaths);
            saved = tx.execute(status -> {
                AppUser txOwner = requireUser(email, "HORSE_OWNER");
                HorseRegistrationRequest request = requestRepository
                        .findByIdAndOwnerIdForUpdate(id.toString(), txOwner.getId())
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu hồ sơ ngựa"));
                if (!"NEEDS_INFORMATION".equals(request.getStatus()))
                    throw new ResponseStatusException(CONFLICT, "Chỉ yêu cầu cần bổ sung mới được chỉnh sửa và gửi lại");
                List<HorseRegistrationDocument> existingDocs = documentRepository
                        .findByRequestIdOrderByUploadedAtAscIdAsc(request.getId());
                List<HorseRegistrationDocument> toRemove = validateResubmitDocumentsAndChanges(
                        request, submission, existingDocs, removeDocIds, types,
                        uploaded.horseImagePath() != null, removeHorseImage);
                if ("REGISTER".equals(request.getRequestType())) {
                    acquireIdentityLocks(db, submission.microchipNumber(), submission.passportNumber());
                    validateUniqueIdentifiers(submission.microchipNumber(), submission.passportNumber(),
                            request.getId(), null);
                }
                validateNoDuplicateActiveRequest(txOwner.getId(), submission, request.getId());

                if (!toRemove.isEmpty()) {
                    toRemove.forEach(doc -> removedStoragePaths.add(doc.getObjectPath()));
                    documentRepository.deleteAll(toRemove);
                    documentRepository.flush();
                }
                applySubmission(request, submission);
                if (uploaded.horseImagePath() != null) {
                    request.setHorseImagePath(uploaded.horseImagePath());
                } else if (removeHorseImage) {
                    request.setHorseImagePath(null);
                }
                request.setRevisionNo(request.getRevisionNo() + 1);
                request.setStatus("PENDING");
                request.setReviewNote(null);
                request.setReviewedBy(null);
                request.setReviewedAt(null);
                request.setTransferConsent(null);
                request.setTransferConsentBy(null);
                request.setTransferConsentAt(null);
                request.setTransferConsentNote(null);
                requestRepository.saveAndFlush(request);

                saveUploadedDocuments(request, validFiles, types, uploaded.documentPaths());
                recordHistory(request, "RESUBMITTED", txOwner.getId(), null);
                if (uploaded.horseImagePath() != null) {
                    audit(txOwner.getId(), "UPLOAD_HORSE_IMAGE:" + uploaded.horseImagePath(), ip);
                }
                audit(txOwner.getId(), "RESUBMIT_HORSE_REGISTRATION: " + request.getId(), ip);
                notifyManagers(request.getId(), "Horse Owner " + txOwner.getFullName()
                        + " đã bổ sung hồ sơ ngựa " + request.getHorseName() + " (lần " + request.getRevisionNo() + ").");
                return request;
            });
        } catch (RuntimeException exception) {
            uploadedPaths.forEach(storage::deleteQuietly);
            throw exception;
        }
        removedStoragePaths.forEach(storage::deleteQuietly);
        return ownerRequestSafe(saved, true);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> ownerRequests(String email) {
        AppUser owner = requireUser(email, "HORSE_OWNER");
        List<HorseRegistrationRequest> requests = requestRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(owner.getId());
        return attachDocumentsAndHistoryBatch(requests, Map.of(), true, false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingRequests(String email) {
        return managerRequests(email, "PENDING");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> managerRequests(String email, String status) {
        requireUser(email, "CLUB_MANAGER");
        String normalized = (status == null || status.isBlank()) ? "PENDING" : status.trim().toUpperCase(Locale.ROOT);
        List<HorseRegistrationRequest> requests;
        if ("PENDING".equals(normalized)) {
            requests = requestRepository.findByStatusOrderByCreatedAtAscIdAsc("PENDING");
        } else if ("ALL".equals(normalized)) {
            requests = requestRepository.findAllByOrderByCreatedAtDescIdDesc();
        } else if (MANAGER_FILTER_STATUSES.contains(normalized)) {
            requests = requestRepository.findByStatusOrderByCreatedAtDescIdDesc(normalized);
        } else {
            throw new ResponseStatusException(BAD_REQUEST, "Trạng thái lọc yêu cầu không hợp lệ");
        }
        Map<Long, AppUser> owners = usersById(requests.stream().map(HorseRegistrationRequest::getOwnerId).toList());
        return attachDocumentsAndHistoryBatch(requests, owners, true, false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> pendingRequestCount(String email) {
        requireUser(email, "CLUB_MANAGER");
        return Map.of("count", requestRepository.countByStatus("PENDING"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> freshDocumentUrlForOwner(String email, String requestId, String docId, boolean download) {
        AppUser owner = requireUser(email, "HORSE_OWNER");
        UUID id = parseUuid(requestId, "Mã yêu cầu không hợp lệ");
        HorseRegistrationRequest request = requestRepository.findByIdAndOwnerId(id.toString(), owner.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu hồ sơ ngựa"));
        return freshDocumentUrl(request.getId(), docId, download);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> freshDocumentUrlForManager(String email, String requestId, String docId, boolean download) {
        requireUser(email, "CLUB_MANAGER");
        UUID id = parseUuid(requestId, "Mã yêu cầu không hợp lệ");
        HorseRegistrationRequest request = requestRepository.findById(id.toString())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu hồ sơ ngựa"));
        return freshDocumentUrl(request.getId(), docId, download);
    }

    private Map<String, Object> freshDocumentUrl(String requestId, String docId, boolean download) {
        HorseRegistrationDocument doc = documentRepository.findByIdAndRequestId(docId, requestId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy giấy tờ xác minh"));
        Map<String, Object> signed = storage.signedUrlFresh(doc.getObjectPath(), download ? doc.getOriginalFilename() : null);
        Object url = signed.get("url");
        if (url == null) {
            throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy tệp giấy tờ");
        }
        return Map.of("url", url, "expiresIn", signed.getOrDefault("expiresIn", 300));
    }

    @Transactional
    public Map<String, Object> review(String managerEmail, String requestId, JsonNode body, String ip) {
        AppUser manager = requireUser(managerEmail, "CLUB_MANAGER");
        UUID id = parseUuid(requestId, "Mã yêu cầu không hợp lệ");
        HorseRegistrationRequest request = requestRepository.findByIdForUpdate(id.toString())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu hồ sơ ngựa"));
        if (!"PENDING".equals(request.getStatus()))
            throw new ResponseStatusException(CONFLICT, "Yêu cầu chưa được gửi lại hoặc đã được xử lý");

        String action = requiredText(body, "action", 30).toUpperCase(Locale.ROOT);
        String note = optionalText(body, "reviewNote", 1000);
        if ("REJECT".equals(action) || "REQUEST_INFORMATION".equals(action)) {
            if (!StringUtils.hasText(note)) {
                String message = "REJECT".equals(action)
                        ? "Vui lòng nhập lý do từ chối."
                        : "Vui lòng nêu rõ thông tin hoặc giấy tờ cần bổ sung.";
                throw new ResponseStatusException(BAD_REQUEST, message);
            }
            request.setStatus("REJECT".equals(action) ? "REJECTED" : "NEEDS_INFORMATION");
            request.setReviewedBy(manager.getId());
            request.setReviewedAt(time.utcNow());
            request.setReviewNote(note);
            requestRepository.saveAndFlush(request);
            String historyAction = "REJECT".equals(action) ? "REJECTED" : "NEEDS_INFORMATION";
            recordHistory(request, historyAction, manager.getId(), note);
            notifyUser(request.getOwnerId(), request.getId(), "REJECT".equals(action)
                    ? "Yêu cầu hồ sơ ngựa " + request.getHorseName() + " chưa được duyệt. Lý do: " + note
                    : "Yêu cầu hồ sơ ngựa " + request.getHorseName() + " cần bổ sung thông tin. Nội dung: " + note);
            audit(manager.getId(), ("REJECT".equals(action) ? "REJECT" : "REQUEST_INFORMATION")
                    + "_HORSE_REGISTRATION: " + id, ip);
            return ownerRequest(request, true);
        }
        if (!"APPROVE".equals(action))
            throw new ResponseStatusException(BAD_REQUEST, "action chỉ nhận APPROVE, REQUEST_INFORMATION hoặc REJECT");

        AppUser owner = userRepository.findById(request.getOwnerId())
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Horse Owner không còn tồn tại"));
        if (!isActiveHorseOwner(owner)) {
            throw new ResponseStatusException(CONFLICT,
                    "Tài khoản Horse Owner gửi yêu cầu không còn hoạt động hoặc đã đổi vai trò");
        }
        String horseId;
        if ("REGISTER".equals(request.getRequestType())) {
            acquireIdentityLocks(db, request.getMicrochipNumber(), request.getPassportNumber());
            UUID stableId = parseUuid(requiredText(body, "stableBoxId", 36), "Cần chọn chuồng cho ngựa mới");
            validateStable(stableId);
            validateUniqueIdentifiers(request.getMicrochipNumber(), request.getPassportNumber(), request.getId(), null);
            Horse horse = createHorseFromRequest(request, stableId, owner.getId());
            horseId = horse.getId();
            ownershipChange(horseId, null, owner.getId(), manager.getId(), "REGISTRATION_APPROVAL");
            request.setStatus("APPROVED");
        } else {
            UUID targetId = parseUuid(requiredText(body, "horseId", 36), "Cần chọn hồ sơ ngựa cần liên kết");
            Horse horse = horseRepository.findActiveByIdForUpdate(targetId.toString())
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy hồ sơ ngựa cần liên kết"));
            if (Objects.equals(horse.getOwnerId(), owner.getId())) {
                throw new ResponseStatusException(CONFLICT, "Hồ sơ ngựa này đã thuộc sở hữu của người gửi yêu cầu");
            }
            acquireIdentityLocks(db, request.getMicrochipNumber(), request.getPassportNumber());
            validateLinkIdentifiers(request, horse);
            horseId = horse.getId();
            if (horse.getOwnerId() != null) {
                AppUser currentOwner = userRepository.findById(horse.getOwnerId()).orElse(null);
                if (!isActiveHorseOwner(currentOwner))
                    throw new ResponseStatusException(CONFLICT,
                            "Chủ sở hữu hiện tại không thể xác nhận chuyển quyền; cần xử lý tài khoản trước");
                if (requestRepository.existsByApprovedHorseIdAndStatus(horseId, "AWAITING_OWNER_CONSENT"))
                    throw new ResponseStatusException(CONFLICT,
                            "Đã có yêu cầu chuyển quyền đang chờ chủ sở hữu hiện tại xác nhận");
                request.setStatus("AWAITING_OWNER_CONSENT");
                request.setApprovedHorseId(horseId);
                request.setReviewedBy(manager.getId());
                request.setReviewedAt(time.utcNow());
                request.setReviewNote(note);
                requestRepository.saveAndFlush(request);
                recordHistory(request, "REVIEW_APPROVED", manager.getId(), note);
                notifyUser(horse.getOwnerId(), request.getId(), "Horse Owner " + owner.getFullName()
                        + " đã đề nghị nhận quyền sở hữu ngựa " + horse.getHorseName()
                        + ". Club Manager đã kiểm tra hồ sơ; vui lòng xác nhận hoặc từ chối trong hệ thống.");
                notifyUser(owner.getId(), request.getId(), "Hồ sơ liên kết đã được Club Manager kiểm tra và đang chờ chủ sở hữu hiện tại xác nhận.");
                audit(manager.getId(), "APPROVE_HORSE_LINK_PENDING_OWNER_CONSENT: " + id + " -> " + horseId, ip);
                return ownerRequest(request, true);
            }
            mergeLinkIdentifiers(request, horse);
            horse.setOwnerId(owner.getId());
            ownershipChange(horseId, null, owner.getId(), manager.getId(), "LINK_APPROVAL");
            if (horse.getImageUrl() == null && request.getHorseImagePath() != null)
                horse.setImageUrl(request.getHorseImagePath());
            horseRepository.saveAndFlush(horse);
            request.setStatus("APPROVED");
        }

        request.setApprovedHorseId(horseId);
        request.setReviewedBy(manager.getId());
        request.setReviewedAt(time.utcNow());
        request.setReviewNote(note);
        requestRepository.saveAndFlush(request);
        recordHistory(request, "REVIEW_APPROVED", manager.getId(), note);
        notifyUser(owner.getId(), request.getId(), "Yêu cầu hồ sơ ngựa " + request.getHorseName() + " đã được duyệt.");
        audit(manager.getId(), "APPROVE_HORSE_REGISTRATION: " + id + " -> " + horseId, ip);
        return ownerRequest(request, true);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> transferRequests(String email) {
        AppUser currentOwner = requireUser(email, "HORSE_OWNER");
        List<String> ownedHorseIds = horseRepository.findByOwnerIdAndDeletedAtIsNullOrderByHorseNameAsc(currentOwner.getId())
                .stream().map(Horse::getId).toList();
        if (ownedHorseIds.isEmpty()) return List.of();
        List<HorseRegistrationRequest> requests = requestRepository
                .findByApprovedHorseIdInAndStatus(ownedHorseIds, "AWAITING_OWNER_CONSENT");
        Map<Long, AppUser> requesters = usersById(requests.stream().map(HorseRegistrationRequest::getOwnerId).toList());
        Map<String, Horse> horses = new HashMap<>();
        horseRepository.findAllById(ownedHorseIds).forEach(horse -> horses.put(horse.getId(), horse));
        return requests.stream().map(request -> {
            Horse horse = horses.get(request.getApprovedHorseId());
            AppUser requester = requesters.get(request.getOwnerId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", request.getId());
            row.put("horse_id", request.getApprovedHorseId());
            row.put("horse_name", horse == null ? request.getHorseName() : horse.getHorseName());
            row.put("requester_name", requester == null ? null : requester.getFullName());
            row.put("requester_email", requester == null ? null : requester.getEmail());
            row.put("owner_note", request.getOwnerNote());
            row.put("review_note", request.getReviewNote());
            row.put("reviewed_at", request.getReviewedAt());
            row.put("created_at", request.getCreatedAt());
            return row;
        }).toList();
    }

    @Transactional
    public Map<String, Object> respondToTransfer(String email, String requestId, JsonNode body, String ip) {
        AppUser currentOwner = requireUser(email, "HORSE_OWNER");
        UUID id = parseUuid(requestId, "Mã yêu cầu không hợp lệ");
        HorseRegistrationRequest request = requestRepository.findByIdForUpdate(id.toString())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy yêu cầu chuyển quyền"));
        if (!"AWAITING_OWNER_CONSENT".equals(request.getStatus()))
            throw new ResponseStatusException(CONFLICT, "Yêu cầu chuyển quyền không còn chờ xác nhận");
        Horse horse = horseRepository.findActiveByIdForUpdate(request.getApprovedHorseId())
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Hồ sơ ngựa không còn hoạt động"));
        if (!Objects.equals(horse.getOwnerId(), currentOwner.getId()))
            throw new ResponseStatusException(FORBIDDEN, "Bạn không phải chủ sở hữu hiện tại của ngựa này");

        String action = requiredText(body, "action", 20).toUpperCase(Locale.ROOT);
        String note = optionalText(body, "note", 1000);
        if (!Set.of("CONSENT", "DECLINE").contains(action))
            throw new ResponseStatusException(BAD_REQUEST, "action chỉ nhận CONSENT hoặc DECLINE");

        request.setTransferConsent("CONSENT".equals(action));
        request.setTransferConsentBy(currentOwner.getId());
        request.setTransferConsentAt(time.utcNow());
        request.setTransferConsentNote(note);
        if ("CONSENT".equals(action)) {
            AppUser requester = userRepository.findById(request.getOwnerId()).orElse(null);
            if (!isActiveHorseOwner(requester)) {
                throw new ResponseStatusException(CONFLICT,
                        "Tài khoản Horse Owner nhận quyền không còn hoạt động hoặc đã đổi vai trò");
            }
            acquireIdentityLocks(db, request.getMicrochipNumber(), request.getPassportNumber());
            mergeLinkIdentifiers(request, horse);
            if (horse.getImageUrl() == null && request.getHorseImagePath() != null)
                horse.setImageUrl(request.getHorseImagePath());
            Long previousOwnerId = horse.getOwnerId();
            horse.setOwnerId(request.getOwnerId());
            horseRepository.saveAndFlush(horse);
            ownershipChange(horse.getId(), previousOwnerId, request.getOwnerId(), currentOwner.getId(), "OWNER_CONSENT");
            request.setStatus("APPROVED");
            notifyUser(request.getOwnerId(), request.getId(), "Chủ sở hữu hiện tại đã đồng ý chuyển quyền ngựa " + horse.getHorseName() + ". Hồ sơ đã được cập nhật.");
            notifyManagers(request.getId(), "Chủ sở hữu hiện tại đã đồng ý chuyển quyền ngựa " + horse.getHorseName() + ".");
        } else {
            request.setStatus("REJECTED");
            request.setReviewNote(StringUtils.hasText(note)
                    ? "Chủ sở hữu hiện tại từ chối chuyển quyền: " + note
                    : "Chủ sở hữu hiện tại không đồng ý chuyển quyền.");
            notifyUser(request.getOwnerId(), request.getId(), "Chủ sở hữu hiện tại không đồng ý chuyển quyền ngựa " + horse.getHorseName() + ".");
            notifyManagers(request.getId(), "Chủ sở hữu hiện tại đã từ chối chuyển quyền ngựa " + horse.getHorseName() + ".");
        }
        requestRepository.saveAndFlush(request);
        recordHistory(request, "CONSENT".equals(action) ? "OWNER_CONSENT" : "OWNER_DECLINED", currentOwner.getId(), note);
        audit(currentOwner.getId(), action + "_HORSE_OWNERSHIP_TRANSFER: " + request.getId(), ip);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", request.getId());
        result.put("status", request.getStatus());
        result.put("horse_id", request.getApprovedHorseId());
        result.put("transfer_consent", request.getTransferConsent());
        result.put("transfer_consent_at", request.getTransferConsentAt());
        return result;
    }

    private Horse createHorseFromRequest(HorseRegistrationRequest request, UUID stableId, Long ownerId) {
        Horse horse = new Horse();
        horse.setId(UUID.randomUUID().toString());
        horse.setHorseName(request.getHorseName());
        horse.setBreed(request.getBreed());
        horse.setBirthYear(request.getBirthYear());
        horse.setHeightCm(request.getHeightCm());
        horse.setCurrentWeightKg(request.getCurrentWeightKg());
        horse.setPedigreeFather(request.getPedigreeFather());
        horse.setPedigreeMother(request.getPedigreeMother());
        horse.setMicrochipNumber(request.getMicrochipNumber());
        horse.setPassportNumber(request.getPassportNumber());
        horse.setImageUrl(request.getHorseImagePath());
        horse.setStableBoxId(stableId.toString());
        horse.setOwnerId(ownerId);
        horse.setCreatedAt(time.utcNow());
        return horseRepository.saveAndFlush(horse);
    }

    private void validateLinkIdentifiers(HorseRegistrationRequest request, Horse horse) {
        String chip = normalizeIdentifier(request.getMicrochipNumber());
        String passport = normalizeIdentifier(request.getPassportNumber());
        if (chip != null && horse.getMicrochipNumber() != null
                && !chip.equalsIgnoreCase(horse.getMicrochipNumber().trim()))
            throw new ResponseStatusException(CONFLICT, "Số microchip không khớp hồ sơ ngựa đã chọn");
        if (passport != null && horse.getPassportNumber() != null
                && !passport.equalsIgnoreCase(horse.getPassportNumber().trim()))
            throw new ResponseStatusException(CONFLICT, "Số hộ chiếu không khớp hồ sơ ngựa đã chọn");
        validateUniqueIdentifiers(chip, passport, request.getId(), horse.getId());
    }

    private void mergeLinkIdentifiers(HorseRegistrationRequest request, Horse horse) {
        validateLinkIdentifiers(request, horse);
        String chip = normalizeIdentifier(request.getMicrochipNumber());
        String passport = normalizeIdentifier(request.getPassportNumber());
        if (horse.getMicrochipNumber() == null) horse.setMicrochipNumber(chip);
        if (horse.getPassportNumber() == null) horse.setPassportNumber(passport);
    }

    private void validateStable(UUID id) {
        var box = stableBoxRepository.findByIdForUpdate(id.toString())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Chuồng không tồn tại"));
        if (!"Normal".equals(box.getBoxType()))
            throw new ResponseStatusException(BAD_REQUEST, "Chuồng cách ly/chấn thương chỉ được điều chuyển bởi bác sĩ thú y");
        long occupied = horseRepository.countByStableBoxIdAndDeletedAtIsNull(id.toString());
        if (!"Available".equals(box.getStatus()) || box.getCapacity() == null || occupied >= box.getCapacity())
            throw new ResponseStatusException(CONFLICT, "Chuồng đã đầy hoặc không khả dụng");
    }

    private void validateUniqueIdentifiers(String chip, String passport, String requestId, String horseId) {
        chip = normalizeIdentifier(chip);
        passport = normalizeIdentifier(passport);
        if (chip != null && ((horseId == null
                ? horseRepository.existsByMicrochipNumberIgnoreCaseAndDeletedAtIsNull(chip)
                : horseRepository.existsByMicrochipNumberIgnoreCaseAndIdNotAndDeletedAtIsNull(chip, horseId))
                || requestRepository.existsOtherActiveMicrochip("REGISTER", ACTIVE_REQUEST_STATUSES, chip, requestId)))
            throw new ResponseStatusException(CONFLICT, "Số microchip đã được dùng cho một hồ sơ khác");
        if (passport != null && ((horseId == null
                ? horseRepository.existsByPassportNumberIgnoreCaseAndDeletedAtIsNull(passport)
                : horseRepository.existsByPassportNumberIgnoreCaseAndIdNotAndDeletedAtIsNull(passport, horseId))
                || requestRepository.existsOtherActivePassport("REGISTER", ACTIVE_REQUEST_STATUSES, passport, requestId)))
            throw new ResponseStatusException(CONFLICT, "Số hộ chiếu đã được dùng cho một hồ sơ khác");
    }

    private void validateNoDuplicateActiveRequest(long ownerId, Submission submission, String excludeId) {
        if (requestRepository.existsDuplicateActiveRequest(
                ownerId,
                submission.requestType(),
                ACTIVE_REQUEST_STATUSES,
                submission.horseName(),
                "REGISTER".equals(submission.requestType()) ? submission.birthYear() : null,
                excludeId)) {
            throw new ResponseStatusException(CONFLICT, "Bạn đã có một yêu cầu đang xử lý cho ngựa này");
        }
    }

    static void acquireIdentityLocks(VetData db, String microchip, String passport) {
        String chip = normalizeIdentifier(microchip);
        String pass = normalizeIdentifier(passport);
        if ((chip == null && pass == null) || !db.isPostgreSQL()) return;
        if (chip != null) {
            db.query("SELECT pg_advisory_xact_lock(7001, hashtext(?))", (rs, rowNum) -> null, chip.toLowerCase(Locale.ROOT));
        }
        if (pass != null) {
            db.query("SELECT pg_advisory_xact_lock(7002, hashtext(?))", (rs, rowNum) -> null, pass.toLowerCase(Locale.ROOT));
        }
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

    private List<MultipartFile> validateFiles(List<MultipartFile> files, JsonNode documentTypes, boolean required) {
        List<MultipartFile> result = files == null ? List.of() : files;
        if ((required && result.isEmpty()) || result.size() > 5)
            throw new ResponseStatusException(BAD_REQUEST,
                    required ? "Đính kèm từ 1 đến 5 giấy tờ xác minh" : "Mỗi lần gửi tối đa 5 giấy tờ");
        for (MultipartFile file : result) {
            if (file == null || file.isEmpty())
                throw new ResponseStatusException(BAD_REQUEST, "Tệp giấy tờ không được để trống");
            if (file.getSize() > 10 * 1024 * 1024)
                throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "Mỗi giấy tờ không được vượt quá 10 MB");
        }
        if (documentTypes != null && !documentTypes.isNull()
                && (!documentTypes.isArray() || documentTypes.size() != result.size()))
            throw new ResponseStatusException(BAD_REQUEST, "Loại giấy tờ không khớp với các tệp đã chọn");
        return result;
    }

    private List<String> parseDocumentTypes(JsonNode input, List<MultipartFile> files) {
        if (files.isEmpty()) return List.of();
        List<String> types = new ArrayList<>();
        if (input == null || input.isNull()) {
            types = new ArrayList<>(Collections.nCopies(files.size(), "OTHER"));
        } else {
            for (JsonNode item : input) {
                String type = item.asText("").trim().toUpperCase(Locale.ROOT);
                if (!DOCUMENT_TYPES.contains(type))
                    throw new ResponseStatusException(BAD_REQUEST, "Loại giấy tờ không hợp lệ");
                types.add(type);
            }
        }
        return types;
    }

    private void validateRequiredDocumentTypes(String requestType, List<String> types) {
        boolean hasOwnership = types.contains("OWNERSHIP_PROOF");
        if ("REGISTER".equals(requestType)) {
            boolean hasHealth = types.contains("HEALTH_DOCUMENT");
            if (!hasOwnership && !hasHealth)
                throw new ResponseStatusException(BAD_REQUEST,
                        "Đăng ký ngựa mới cần đính kèm ít nhất 1 Giấy tờ chứng minh quyền sở hữu và 1 Hồ sơ sức khỏe");
            if (!hasOwnership)
                throw new ResponseStatusException(BAD_REQUEST,
                        "Đăng ký ngựa mới cần đính kèm ít nhất 1 Giấy tờ chứng minh quyền sở hữu");
            if (!hasHealth)
                throw new ResponseStatusException(BAD_REQUEST,
                        "Đăng ký ngựa mới cần đính kèm ít nhất 1 Hồ sơ sức khỏe");
            return;
        }
        if (!hasOwnership)
            throw new ResponseStatusException(BAD_REQUEST,
                    "Liên kết hồ sơ ngựa cần đính kèm ít nhất 1 Giấy tờ chứng minh quyền sở hữu");
    }

    private Set<String> parseRemoveDocumentIds(JsonNode body) {
        if (body == null || !body.has("removeDocumentIds") || body.get("removeDocumentIds").isNull())
            return Set.of();
        JsonNode node = body.get("removeDocumentIds");
        if (!node.isArray())
            throw new ResponseStatusException(BAD_REQUEST, "Danh sách giấy tờ cần gỡ không hợp lệ");
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : node) {
            ids.add(parseUuid(item.asText("").trim(), "Mã giấy tờ cần gỡ không hợp lệ").toString());
        }
        return ids;
    }

    private List<HorseRegistrationDocument> validateResubmitDocumentsAndChanges(
            HorseRegistrationRequest request,
            Submission submission,
            List<HorseRegistrationDocument> existingDocs,
            Set<String> removeDocIds,
            List<String> newTypes,
            boolean hasNewImage,
            boolean removeHorseImage) {
        Map<String, HorseRegistrationDocument> byId = new HashMap<>();
        for (HorseRegistrationDocument doc : existingDocs) {
            byId.put(doc.getId(), doc);
        }
        for (String docId : removeDocIds) {
            if (!byId.containsKey(docId))
                throw new ResponseStatusException(BAD_REQUEST, "Không tìm thấy giấy tờ cần gỡ trong yêu cầu này");
        }
        List<HorseRegistrationDocument> toRemove = new ArrayList<>();
        List<HorseRegistrationDocument> remaining = new ArrayList<>();
        for (HorseRegistrationDocument doc : existingDocs) {
            if (removeDocIds.contains(doc.getId())) {
                toRemove.add(doc);
            } else {
                remaining.add(doc);
            }
        }
        if (remaining.size() + newTypes.size() > 5) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Tổng số giấy tờ hiện hành tối đa là 5 tệp (hiện còn "
                            + remaining.size() + " tệp, hãy gỡ bớt tệp cũ nếu muốn tải thêm)");
        }
        List<String> resultingTypes = new ArrayList<>(remaining.size() + newTypes.size());
        remaining.forEach(doc -> resultingTypes.add(doc.getDocumentType()));
        resultingTypes.addAll(newTypes);
        validateRequiredDocumentTypes(request.getRequestType(), resultingTypes);

        boolean imageChanged = hasNewImage || (removeHorseImage && request.getHorseImagePath() != null);
        boolean docsChanged = !toRemove.isEmpty() || !newTypes.isEmpty();
        if (!imageChanged && !docsChanged && !hasSubmissionChanged(request, submission)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Bạn chưa thay đổi thông tin hoặc bổ sung/thay thế giấy tờ nào");
        }
        return toRemove;
    }

    private static boolean hasSubmissionChanged(HorseRegistrationRequest request, Submission submission) {
        return !Objects.equals(normalizeIdentifier(request.getHorseName()), submission.horseName())
                || !Objects.equals(normalizeIdentifier(request.getBreed()), submission.breed())
                || !Objects.equals(request.getBirthYear(), submission.birthYear())
                || !sameDecimal(request.getHeightCm(), submission.heightCm())
                || !sameDecimal(request.getCurrentWeightKg(), submission.currentWeightKg())
                || !Objects.equals(normalizeIdentifier(request.getPedigreeFather()), submission.pedigreeFather())
                || !Objects.equals(normalizeIdentifier(request.getPedigreeMother()), submission.pedigreeMother())
                || !Objects.equals(normalizeIdentifier(request.getMicrochipNumber()), submission.microchipNumber())
                || !Objects.equals(normalizeIdentifier(request.getPassportNumber()), submission.passportNumber())
                || !Objects.equals(normalizeIdentifier(request.getOwnerNote()), submission.ownerNote());
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) return true;
        if (left == null || right == null) return false;
        return left.compareTo(right) == 0;
    }

    private record UploadedRegistrationAssets(String horseImagePath, List<String> documentPaths) {}

    private UploadedRegistrationAssets uploadAssetsInParallel(MultipartFile horseImage, long ownerId,
            List<MultipartFile> files, List<String> uploadedPaths) {
        boolean hasHorseImage = horseImage != null && !horseImage.isEmpty();
        List<java.util.function.Supplier<String>> tasks = new ArrayList<>();
        if (hasHorseImage) {
            tasks.add(() -> {
                String path = storage.uploadRegistrationHorseImage(horseImage, ownerId);
                if (path != null) uploadedPaths.add(path);
                return path;
            });
        }
        for (MultipartFile file : files) {
            tasks.add(() -> {
                String path = storage.uploadRegistrationDocument(file);
                if (path != null) uploadedPaths.add(path);
                return path;
            });
        }
        if (tasks.isEmpty()) {
            return new UploadedRegistrationAssets(null, List.of());
        }
        List<String> uploaded = parallelMap(tasks, java.util.function.Supplier::get, "Không tải lên được tệp hồ sơ ngựa");
        String imagePath = hasHorseImage ? uploaded.getFirst() : null;
        List<String> docPaths = hasHorseImage ? uploaded.subList(1, uploaded.size()) : uploaded;
        return new UploadedRegistrationAssets(imagePath, docPaths);
    }

    private void saveUploadedDocuments(HorseRegistrationRequest request, List<MultipartFile> files, List<String> types, List<String> paths) {
        if (files.isEmpty()) return;
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            HorseRegistrationDocument document = new HorseRegistrationDocument();
            document.setId(UUID.randomUUID().toString());
            document.setRequestId(request.getId());
            document.setObjectPath(paths.get(index));
            document.setOriginalFilename(safeFilename(file.getOriginalFilename()));
            document.setContentType(file.getContentType());
            document.setFileSize(file.getSize());
            document.setDocumentType(types.get(index));
            document.setRevisionNo(request.getRevisionNo());
            document.setUploadedAt(time.utcNow());
            documentRepository.save(document);
        }
        documentRepository.flush();
    }

    private List<Map<String, Object>> attachDocumentsAndHistoryBatch(
            List<HorseRegistrationRequest> requests,
            Map<Long, AppUser> owners,
            boolean includeHistory,
            boolean ignoreSigningErrors) {
        if (requests.isEmpty()) return List.of();
        List<String> requestIds = requests.stream().map(HorseRegistrationRequest::getId).toList();
        Map<String, List<HorseRegistrationDocument>> docsByRequest = new HashMap<>();
        for (HorseRegistrationDocument doc : documentRepository.findByRequestIdInOrderByUploadedAtAscIdAsc(requestIds)) {
            docsByRequest.computeIfAbsent(doc.getRequestId(), k -> new ArrayList<>()).add(doc);
        }
        List<String> allPaths = new ArrayList<>();
        for (HorseRegistrationRequest req : requests) {
            if (req.getHorseImagePath() != null) allPaths.add(req.getHorseImagePath());
            for (HorseRegistrationDocument doc : docsByRequest.getOrDefault(req.getId(), List.of())) {
                allPaths.add(doc.getObjectPath());
            }
        }
        List<String> signedUrls = parallelMap(allPaths,
                path -> {
                    try {
                        return Objects.toString(storage.signedUrl(Objects.toString(path, "")).get("url"), null);
                    } catch (RuntimeException exception) {
                        if (ignoreSigningErrors) return null;
                        throw exception;
                    }
                },
                "Không tạo được liên kết giấy tờ xác minh");
        int cursor = 0;
        List<Map<String, Object>> result = new ArrayList<>(requests.size());
        for (HorseRegistrationRequest req : requests) {
            Map<String, Object> row = requestMap(req);
            AppUser owner = owners.get(req.getOwnerId());
            if (owner != null) {
                row.put("owner_name", owner.getFullName());
                row.put("owner_email", owner.getEmail());
                row.put("owner_phone", owner.getPhone());
            }
            row.put("horse_image_url", req.getHorseImagePath() == null ? null : signedUrls.get(cursor++));
            List<Map<String, Object>> docMaps = new ArrayList<>();
            for (HorseRegistrationDocument doc : docsByRequest.getOrDefault(req.getId(), List.of())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", doc.getId());
                item.put("name", doc.getOriginalFilename());
                item.put("content_type", doc.getContentType());
                item.put("file_size", doc.getFileSize());
                item.put("document_type", doc.getDocumentType());
                item.put("revision_no", doc.getRevisionNo());
                item.put("url", signedUrls.get(cursor++));
                docMaps.add(item);
            }
            row.put("documents", docMaps);
            if (includeHistory) {
                row.put("history", parseHistoryList(req.getHistoryJson(), objectMapper));
            }
            result.add(row);
        }
        return result;
    }

    private static List<Map<String, Object>> parseHistoryList(String historyJson, ObjectMapper mapper) {
        if (historyJson == null || historyJson.isBlank()) return List.of();
        try {
            return mapper.readValue(historyJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> ownerRequest(HorseRegistrationRequest request, boolean includeHistory) {
        return attachDocumentsAndHistoryBatch(List.of(request), Map.of(), includeHistory, false).getFirst();
    }

    private Map<String, Object> ownerRequestSafe(HorseRegistrationRequest request, boolean includeHistory) {
        return attachDocumentsAndHistoryBatch(List.of(request), Map.of(), includeHistory, true).getFirst();
    }

    private Map<String, Object> requestMap(HorseRegistrationRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", request.getId());
        row.put("owner_id", request.getOwnerId());
        row.put("request_type", request.getRequestType());
        row.put("horse_name", request.getHorseName());
        row.put("breed", request.getBreed());
        row.put("birth_year", request.getBirthYear());
        row.put("height_cm", request.getHeightCm());
        row.put("current_weight_kg", request.getCurrentWeightKg());
        row.put("pedigree_father", request.getPedigreeFather());
        row.put("pedigree_mother", request.getPedigreeMother());
        row.put("microchip_number", request.getMicrochipNumber());
        row.put("passport_number", request.getPassportNumber());
        row.put("owner_note", request.getOwnerNote());
        row.put("horse_image_path", request.getHorseImagePath());
        row.put("status", request.getStatus());
        row.put("approved_horse_id", request.getApprovedHorseId());
        row.put("reviewed_by", request.getReviewedBy());
        row.put("review_note", request.getReviewNote());
        row.put("revision_no", request.getRevisionNo());
        row.put("transfer_consent", request.getTransferConsent());
        row.put("transfer_consent_by", request.getTransferConsentBy());
        row.put("transfer_consent_at", request.getTransferConsentAt());
        row.put("transfer_consent_note", request.getTransferConsentNote());
        row.put("created_at", request.getCreatedAt());
        row.put("reviewed_at", request.getReviewedAt());
        return row;
    }

    private Map<Long, AppUser> usersById(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, AppUser> result = new HashMap<>();
        userRepository.findAllById(ids.stream().distinct().toList()).forEach(user -> result.put(user.getId(), user));
        return result;
    }

    private void recordHistory(HorseRegistrationRequest request, String action, Long actorId, String note) {
        appendHistoryEntry(request, action, actorId, note, time.utcNow(), objectMapper);
        requestRepository.save(request);
    }

    static void appendHistoryEntry(HorseRegistrationRequest request, String action, Long actorId,
            String note, LocalDateTime createdAt, ObjectMapper mapper) {
        try {
            List<Map<String, Object>> historyList = new ArrayList<>(parseHistoryList(request.getHistoryJson(), mapper));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", UUID.randomUUID().toString());
            item.put("revision_no", request.getRevisionNo());
            item.put("action", action);
            item.put("actor_id", actorId);
            item.put("note", note);
            item.put("snapshot_json", snapshot(request, mapper));
            item.put("created_at", createdAt.toString());
            historyList.add(item);
            request.setHistoryJson(mapper.writeValueAsString(historyList));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize request history", e);
        }
    }

    private static String snapshot(HorseRegistrationRequest request, ObjectMapper mapper) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("requestType", request.getRequestType());
        snapshot.put("horseName", request.getHorseName());
        snapshot.put("breed", request.getBreed());
        snapshot.put("birthYear", request.getBirthYear());
        snapshot.put("heightCm", request.getHeightCm());
        snapshot.put("currentWeightKg", request.getCurrentWeightKg());
        snapshot.put("pedigreeFather", request.getPedigreeFather());
        snapshot.put("pedigreeMother", request.getPedigreeMother());
        snapshot.put("microchipNumber", request.getMicrochipNumber());
        snapshot.put("passportNumber", request.getPassportNumber());
        snapshot.put("ownerNote", request.getOwnerNote());
        snapshot.put("horseImagePath", request.getHorseImagePath());
        snapshot.put("status", request.getStatus());
        snapshot.put("approvedHorseId", request.getApprovedHorseId());
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save horse registration revision", exception);
        }
    }

    private void applySubmission(HorseRegistrationRequest request, Submission submission) {
        request.setHorseName(submission.horseName());
        request.setBreed(submission.breed());
        request.setBirthYear(submission.birthYear());
        request.setHeightCm(submission.heightCm());
        request.setCurrentWeightKg(submission.currentWeightKg());
        request.setPedigreeFather(submission.pedigreeFather());
        request.setPedigreeMother(submission.pedigreeMother());
        request.setMicrochipNumber(submission.microchipNumber());
        request.setPassportNumber(submission.passportNumber());
        request.setOwnerNote(submission.ownerNote());
    }

    private Submission parseSubmission(JsonNode body, String expectedType) {
        String requestType = expectedType == null
                ? requiredText(body, "requestType", 20).toUpperCase(Locale.ROOT)
                : expectedType;
        if (!Set.of("REGISTER", "LINK").contains(requestType))
            throw new ResponseStatusException(BAD_REQUEST, "requestType chỉ nhận REGISTER hoặc LINK");
        if (body != null && body.hasNonNull("requestType")
                && !requestType.equals(body.path("requestType").asText("").trim().toUpperCase(Locale.ROOT)))
            throw new ResponseStatusException(BAD_REQUEST, "Không thể đổi loại yêu cầu khi gửi bổ sung");
        String horseName = requiredText(body, "horseName", 100);
        if (horseName.length() < 2)
            throw new ResponseStatusException(BAD_REQUEST, "Tên ngựa phải có ít nhất 2 ký tự");
        Integer birthYear = optionalInt(body, "birthYear");
        if (birthYear != null && (birthYear < 1900 || birthYear > time.today().getYear()))
            throw new ResponseStatusException(BAD_REQUEST, "Năm sinh không hợp lệ");
        BigDecimal height = optionalDecimal(body, "heightCm", new BigDecimal("50"), new BigDecimal("250"), 1);
        BigDecimal weight = optionalDecimal(body, "currentWeightKg", new BigDecimal("100"), new BigDecimal("900"), 2);
        return new Submission(requestType, horseName, optionalText(body, "breed", 50), birthYear,
                height, weight, optionalText(body, "pedigreeFather", 100),
                optionalText(body, "pedigreeMother", 100), optionalText(body, "microchipNumber", 100),
                optionalText(body, "passportNumber", 100), optionalText(body, "ownerNote", 1000));
    }

    private AppUser requireUser(String email, String role) {
        AppUser user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "Tài khoản không có quyền thao tác hồ sơ ngựa"));
        if (user.getStatus() != UserStatus.APPROVED || user.getDeletedAt() != null
                || user.isMustChangePassword() || user.getRole() == null
                || !role.equals(user.getRole().getName()))
            throw new ResponseStatusException(FORBIDDEN, "Tài khoản không có quyền thao tác hồ sơ ngựa");
        return user;
    }

    private static boolean isActiveHorseOwner(AppUser user) {
        return user != null
                && user.getStatus() == UserStatus.APPROVED
                && user.getDeletedAt() == null
                && !user.isMustChangePassword()
                && user.getRole() != null
                && "HORSE_OWNER".equals(user.getRole().getName());
    }

    private void notifyManagers(String requestId, String message) {
        for (AppUser manager : userRepository.findActiveByRoleName("CLUB_MANAGER", UserStatus.APPROVED)) {
            if (!manager.isMustChangePassword()) {
                notifyUser(manager.getId(), requestId, message);
            }
        }
    }

    private void notifyUser(long userId, String requestId, String message) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID().toString());
        notification.setUserId(userId);
        notification.setRelatedTable("horse_registration_requests");
        notification.setRelatedId(requestId);
        notification.setMessage(message);
        notification.setRead(false);
        notification.setCreatedAt(time.utcNow());
        notificationRepository.save(notification);
    }

    private void audit(long actorId, String action, String ip) {
        AuditLog log = new AuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setUserId(actorId);
        log.setActionPerformed(action);
        log.setIpAddress(ip);
        log.setCreatedAt(time.utcNow());
        auditLogRepository.save(log);
    }

    private static String fieldLabel(String key) {
        return FIELD_LABELS.getOrDefault(key, key);
    }

    private static String requiredText(JsonNode body, String key, int max) {
        String value = body == null ? "" : body.path(key).asText("").trim();
        if (!StringUtils.hasText(value))
            throw new ResponseStatusException(BAD_REQUEST, fieldLabel(key) + " không được để trống");
        if (value.length() > max)
            throw new ResponseStatusException(BAD_REQUEST, fieldLabel(key) + " vượt quá " + max + " ký tự");
        return value;
    }

    private static String optionalText(JsonNode body, String key, int max) {
        String value = body == null ? "" : body.path(key).asText("").trim();
        if (value.isEmpty()) return null;
        if (value.length() > max)
            throw new ResponseStatusException(BAD_REQUEST, fieldLabel(key) + " vượt quá " + max + " ký tự");
        return value;
    }

    private static Integer optionalInt(JsonNode body, String key) {
        JsonNode value = body == null ? null : body.get(key);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt())
            throw new ResponseStatusException(BAD_REQUEST, fieldLabel(key) + " phải là số nguyên hợp lệ");
        return value.asInt();
    }

    private static BigDecimal optionalDecimal(JsonNode body, String key, BigDecimal min,
            BigDecimal max, int maxScale) {
        JsonNode value = body == null ? null : body.get(key);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        if (!value.isNumber())
            throw new ResponseStatusException(BAD_REQUEST, fieldLabel(key) + " phải là số hợp lệ");
        BigDecimal number = value.decimalValue();
        if (number.compareTo(min) < 0 || number.compareTo(max) > 0)
            throw new ResponseStatusException(BAD_REQUEST, fieldLabel(key) + " phải nằm trong khoảng " + min + " đến " + max);
        if (number.scale() > maxScale)
            throw new ResponseStatusException(BAD_REQUEST, fieldLabel(key) + " chỉ được có tối đa " + maxScale + " chữ số thập phân");
        return number;
    }

    private static String normalizeIdentifier(String value) {
        if (!StringUtils.hasText(value)) return null;
        return value.trim();
    }

    private static UUID parseUuid(String value, String message) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(BAD_REQUEST, message); }
    }

    private static String safeFilename(String original) {
        String value = Objects.toString(original, "giay-to").replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.isEmpty()) value = "giay-to";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private static <T, R> List<R> parallelMap(List<T> values, Function<T, R> operation, String errorMessage) {
        if (values.isEmpty()) return List.of();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<R>> futures = values.stream()
                    .map(value -> CompletableFuture.supplyAsync(() -> operation.apply(value), executor)).toList();
            List<R> results = new ArrayList<>(Collections.nCopies(values.size(), null));
            RuntimeException failure = null;
            for (int index = 0; index < futures.size(); index++) {
                try {
                    results.set(index, futures.get(index).join());
                } catch (CompletionException exception) {
                    Throwable cause = exception.getCause();
                    if (failure == null) failure = cause instanceof RuntimeException runtime
                            ? runtime : new IllegalStateException(errorMessage, cause);
                }
            }
            if (failure != null) throw failure;
            return results;
        }
    }

    private record Submission(String requestType, String horseName, String breed, Integer birthYear,
            BigDecimal heightCm, BigDecimal currentWeightKg, String pedigreeFather, String pedigreeMother,
            String microchipNumber, String passportNumber, String ownerNote) {}
}
