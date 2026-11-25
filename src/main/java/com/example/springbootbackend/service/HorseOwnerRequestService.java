package com.example.springbootbackend.service;

import static org.springframework.http.HttpStatus.*;

import com.example.springbootbackend.dto.request.HorseRegistrationRequest;
import com.example.springbootbackend.dto.request.OwnershipTransferRequest;
import com.example.springbootbackend.repository.HorseOwnerRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
/** Persistent, owner-scoped submissions; approval is a separate operation. */
public class HorseOwnerRequestService {
  private final VetData db;
  private final HorseOwnerRepository owners;
  private final VetTime time;
  private final RegistrationStorage storage;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final com.example.springbootbackend.service.HorseStorage legacyStorage;
  private final HorseRegistrationService modern;

  public HorseOwnerRequestService(
      VetData db,
      HorseOwnerRepository owners,
      VetTime time,
      RegistrationStorage storage,
      com.fasterxml.jackson.databind.ObjectMapper json,
      com.example.springbootbackend.service.HorseStorage legacyStorage,
      HorseRegistrationService modern) {
    this.db = db;
    this.owners = owners;
    this.time = time;
    this.storage = storage;
    this.json = json;
    this.legacyStorage = legacyStorage;
    this.modern = modern;
  }

  private static final String REGISTRATION_SELECT =
      "SELECT r.*,owner_id AS requester_id,owner_note AS reason FROM horse_registration_requests r"
          + " ";

  public Map<String, Object> registrations(String email) {
    long owner = requireOwner(email);
    var items =
        db.query(
            REGISTRATION_SELECT + "WHERE owner_id=? ORDER BY created_at DESC,id DESC",
            VetRows.MAPPER,
            owner);
    items.forEach(this::withAttachments);
    return Map.of("items", items, "total", items.size());
  }

  public Map<String, Object> registration(String email, String id) {
    return withAttachments(find(id, requireOwner(email), false));
  }

  private Map<String, Object> find(String id, long owner, boolean lock) {
    var rows =
        db.query(
            REGISTRATION_SELECT + "WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""),
            VetRows.MAPPER,
            id,
            owner);
    if (rows.isEmpty())
      throw new ResponseStatusException(NOT_FOUND, "Registration request not found");
    return rows.getFirst();
  }

  /**
   * Internal response projection; caller must authorize access to the supplied registration row.
   */
  public Map<String, Object> withAttachments(Map<String, Object> row) {
    String id = row.get("id").toString();
    var documents =
        db.query(
            "SELECT * FROM horse_registration_documents WHERE request_id=? ORDER BY uploaded_at,id",
            VetRows.MAPPER,
            id);
    for (var document : documents) {
      String path = document.get("object_path").toString();
      if (path.startsWith("horse-documents/cloudinary/") || path.startsWith("horses/cloudinary/")) {
        var asset = legacyStorage.signedUrl(path);
        document.put("url", asset.get("url"));
        document.put("available", asset.getOrDefault("available", true));
        if (asset.containsKey("unavailableReason"))
          document.put("unavailableReason", asset.get("unavailableReason"));
      } else document.put("url", storage.signedUrl(path));
    }
    row.put("documents", documents);
    var history = new ArrayList<Object>();
    Object legacy = row.remove("history_json");
    if (legacy != null) {
      try {
        history.addAll(
            json.readValue(
                legacy.toString(),
                new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {}));
      } catch (java.io.IOException e) {
        throw new ResponseStatusException(
            INTERNAL_SERVER_ERROR, "Cannot read registration history");
      }
    }
    history.addAll(
        db.query(
            "SELECT id,actor_id,action,review_note,from_status,to_status,created_at FROM"
                + " horse_registration_history WHERE request_id=? ORDER BY created_at,id",
            VetRows.MAPPER,
            id));
    row.put("history", history);
    Object image = row.get("horse_image_path");
    row.put(
        "horseImageUrl",
        image == null
            ? null
            : (image.toString().startsWith("registrations/")
                ? storage.signedUrl(image.toString())
                : legacyStorage.signedUrl(image.toString()).get("url")));
    row.put("horse_image_url", row.get("horseImageUrl"));
    return row;
  }

  public Map<String, Object> transfers(String email) {
    long owner = requireOwner(email);
    var items =
        db.query(
            "SELECT r.*,h.horse_name,u.email AS new_owner_email FROM"
                + " owner_ownership_transfer_requests r JOIN horses h ON h.id=r.horse_id JOIN users"
                + " u ON u.user_id=r.new_owner_id WHERE r.requester_id=? ORDER BY r.created_at"
                + " DESC,r.id DESC",
            VetRows.MAPPER,
            owner);
    return Map.of("items", items, "total", items.size());
  }

  @Transactional
  public Map<String, Object> register(String email, HorseRegistrationRequest input) {
    return save(email, null, input, null, List.of(), List.of());
  }

  @Transactional
  public Map<String, Object> save(
      String email,
      String id,
      HorseRegistrationRequest input,
      MultipartFile image,
      List<MultipartFile> documents,
      List<String> documentTypes) {
    long owner = requireOwner(email);
    boolean creating = id == null;
    Map<String, Object> previous = creating ? Map.of() : find(id, owner, true);
    if (input != null
        && "REGISTER"
            .equals(
                creating
                    ? (input.requestType() == null ? "REGISTER" : input.requestType())
                    : previous.get("request_type"))) modern.validateLegacyIdentifiers(input, id);
    if (!creating && !Set.of("PENDING", "NEEDS_INFORMATION").contains(previous.get("status")))
      throw new ResponseStatusException(
          CONFLICT, "Only pending or needs-information requests can be supplemented");
    documents = documents == null ? List.of() : documents;
    documentTypes = documentTypes == null ? List.of() : documentTypes;
    if (documents.size() > 5)
      throw new ResponseStatusException(BAD_REQUEST, "At most 5 documents per request");
    if (documents.size() != documentTypes.size())
      throw new ResponseStatusException(BAD_REQUEST, "documentTypes must match documents");
    var allowed = Set.of("OWNERSHIP_PROOF", "HORSE_CERTIFICATE", "HEALTH_DOCUMENT", "OTHER");
    for (String type : documentTypes)
      if (type == null || !allowed.contains(type))
        throw new ResponseStatusException(BAD_REQUEST, "Invalid document type");
    var imageData = image == null ? null : storage.validate(image, true);
    var files = documents.stream().map(file -> storage.validate(file, false)).toList();
    String name = input == null ? null : input.horseName();
    if (creating && (name == null || name.isBlank()))
      throw new ResponseStatusException(BAD_REQUEST, "horseName is required");
    if (name != null && name.trim().length() < 2)
      throw new ResponseStatusException(
          BAD_REQUEST, "horseName must contain at least 2 characters");
    if (input != null && input.birthYear() != null && input.birthYear() > time.today().getYear())
      throw new ResponseStatusException(BAD_REQUEST, "birthYear cannot be in the future");
    if (creating) {
      if (input == null || name == null)
        throw new ResponseStatusException(BAD_REQUEST, "horseName is required");
      id = UUID.randomUUID().toString();
      db.update(
          "INSERT INTO horse_registration_requests(id,owner_id,request_type,horse_name,created_at)"
              + " VALUES (?,?,?,?,?)",
          id,
          owner,
          input.requestType() == null ? "REGISTER" : input.requestType(),
          name.trim(),
          time.utcNow());
    }
    int revision = creating ? 1 : ((Number) previous.get("revision_no")).intValue() + 1;
    if (!creating)
      db.update(
          "UPDATE horse_registration_requests SET revision_no=?,status='PENDING' WHERE id=?",
          revision,
          id);
    if (input != null) {
      var values = new LinkedHashMap<String, Object>();
      values.put("horse_name", name == null ? null : name.trim());
      values.put("request_type", input.requestType());
      values.put("breed", input.breed());
      values.put("birth_year", input.birthYear());
      values.put("height_cm", input.heightCm());
      values.put("current_weight_kg", input.currentWeightKg());
      values.put("pedigree_father", input.pedigreeFather());
      values.put("pedigree_mother", input.pedigreeMother());
      values.put("microchip_number", input.microchipNumber());
      values.put("passport_number", input.passportNumber());
      values.put("owner_note", input.reason());
      for (var entry : values.entrySet())
        if (entry.getValue() != null)
          db.update(
              "UPDATE horse_registration_requests SET " + entry.getKey() + "=? WHERE id=?",
              entry.getValue(),
              id);
    }
    if (imageData != null)
      db.update(
          "UPDATE horse_registration_requests SET"
              + " horse_image_path=?,horse_image_filename=?,horse_image_content_type=?,horse_image_file_size=?"
              + " WHERE id=?",
          storage.upload(id, imageData),
          imageData.filename(),
          imageData.type(),
          imageData.bytes().length,
          id);
    for (int i = 0; i < files.size(); i++) {
      var file = files.get(i);
      db.update(
          "INSERT INTO"
              + " horse_registration_documents(id,request_id,object_path,original_filename,content_type,file_size,document_type,uploaded_at,revision_no)"
              + " VALUES (?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          id,
          storage.upload(id, file),
          file.filename(),
          file.type(),
          file.bytes().length,
          documentTypes.get(i),
          time.utcNow(),
          revision);
    }
    db.update(
        "INSERT INTO horse_registration_history(id,request_id,actor_id,action,created_at) VALUES"
            + " (?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        owner,
        creating ? "SUBMITTED" : "SUPPLEMENTED",
        time.utcNow());
    return withAttachments(find(id, owner, false));
  }

  @Transactional
  public Map<String, Object> transfer(String email, OwnershipTransferRequest input) {
    long owner = requireOwner(email);
    String horse = input.horseId().toString();
    // Serialize submissions for a horse so parallel requests cannot create duplicate pending
    // transfers.
    var horses =
        db.queryForList(
            "SELECT owner_id FROM horses WHERE id=? AND deleted_at IS NULL FOR UPDATE", horse);
    if (horses.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa");
    if (!(horses.getFirst().get("owner_id") instanceof Number current)
        || current.longValue() != owner)
      throw new ResponseStatusException(
          FORBIDDEN, "Chỉ chủ sở hữu hiện tại mới được yêu cầu chuyển quyền");
    Long recipient = owners.findApprovedOwnerId(input.newOwnerEmail().trim());
    if (recipient == null)
      throw new ResponseStatusException(
          BAD_REQUEST, "Chủ mới phải là HORSE_OWNER đã được duyệt và đang hoạt động");
    if (recipient == owner)
      throw new ResponseStatusException(BAD_REQUEST, "Không thể chuyển quyền cho chính mình");
    if (db.queryForObject(
            "SELECT count(*) FROM owner_ownership_transfer_requests WHERE horse_id=? AND"
                + " status='PENDING'",
            Long.class,
            horse)
        > 0)
      throw new ResponseStatusException(CONFLICT, "Ngựa đã có yêu cầu chuyển quyền đang chờ duyệt");
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " owner_ownership_transfer_requests(id,requester_id,horse_id,new_owner_id,reason,created_at)"
            + " VALUES (?,?,?,?,?,?)",
        id,
        owner,
        horse,
        recipient,
        input.reason(),
        time.utcNow());
    return db.query(
            "SELECT r.*,h.horse_name,u.email AS new_owner_email FROM"
                + " owner_ownership_transfer_requests r JOIN horses h ON h.id=r.horse_id JOIN users"
                + " u ON u.user_id=r.new_owner_id WHERE r.id=? AND r.requester_id=?",
            VetRows.MAPPER,
            id,
            owner)
        .getFirst();
  }

  private long requireOwner(String email) {
    Long owner = owners.findApprovedOwnerId(email);
    if (owner == null)
      throw new ResponseStatusException(
          FORBIDDEN, "Chỉ HORSE_OWNER đã được duyệt mới được gửi và xem yêu cầu");
    return owner;
  }
}
