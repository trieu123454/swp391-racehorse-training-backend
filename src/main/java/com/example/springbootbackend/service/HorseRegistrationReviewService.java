package com.example.springbootbackend.service;

import static org.springframework.http.HttpStatus.*;

import com.example.springbootbackend.dto.request.ReviewHorseRegistrationRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class HorseRegistrationReviewService {
  private final VetData db;
  private final VetTime time;
  private final HorseOwnerRequestService views;
  private final NotificationService notifications;

  public HorseRegistrationReviewService(
      VetData db, VetTime time, HorseOwnerRequestService views, NotificationService notifications) {
    this.db = db;
    this.time = time;
    this.views = views;
    this.notifications = notifications;
  }

  private long requireManager(String email) {
    var rows =
        db.queryForList(
            "SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id WHERE"
                + " lower(u.email)=lower(?) AND r.role_name='CLUB_MANAGER' AND u.status='APPROVED'"
                + " AND u.must_change_password=FALSE AND u.deleted_at IS NULL",
            email);
    if (rows.isEmpty())
      throw new ResponseStatusException(FORBIDDEN, "An active approved Club Manager is required");
    return ((Number) rows.getFirst().get("user_id")).longValue();
  }

  public List<Map<String, Object>> list(String email, String status) {
    requireManager(email);
    if (status != null && !Set.of("PENDING", "NEEDS_INFORMATION").contains(status))
      throw new ResponseStatusException(BAD_REQUEST, "status must be PENDING or NEEDS_INFORMATION");
    var rows =
        db.query(
            "SELECT r.*,r.owner_id AS requester_id,r.owner_note AS reason,u.full_name AS"
                + " owner_name,u.email AS owner_email,u.phone AS owner_phone FROM"
                + " horse_registration_requests r JOIN users u ON u.user_id=r.owner_id WHERE "
                + (status == null ? "r.status IN ('PENDING','NEEDS_INFORMATION')" : "r.status=?")
                + " ORDER BY r.created_at,r.id",
            VetRows.MAPPER,
            status == null ? new Object[0] : new Object[] {status});
    rows.forEach(this::project);
    return rows;
  }

  private Map<String, Object> project(Map<String, Object> row) {
    if (!row.containsKey("owner_name")) {
      var owner =
          db.queryForMap(
              "SELECT full_name,email,phone FROM users WHERE user_id=?", row.get("owner_id"));
      row.put("owner_name", owner.get("full_name"));
      row.put("owner_email", owner.get("email"));
      row.put("owner_phone", owner.get("phone"));
    }
    var owner = new LinkedHashMap<String, Object>();
    owner.put("id", row.get("owner_id"));
    owner.put("full_name", row.get("owner_name"));
    owner.put("email", row.get("owner_email"));
    owner.put("phone", row.get("owner_phone"));
    row.put("owner", owner);
    return views.withAttachments(row);
  }

  @Transactional
  public Map<String, Object> review(
      String email, UUID requestId, ReviewHorseRegistrationRequest input) {
    long manager = requireManager(email);
    String id = requestId.toString();
    var rows =
        db.query(
            "SELECT r.*,owner_id AS requester_id,owner_note AS reason FROM"
                + " horse_registration_requests r WHERE id=? FOR UPDATE",
            VetRows.MAPPER,
            id);
    if (rows.isEmpty())
      throw new ResponseStatusException(NOT_FOUND, "Registration request not found");
    var row = rows.getFirst();
    String old = row.get("status").toString();
    if (!Set.of("PENDING", "NEEDS_INFORMATION").contains(old))
      throw new ResponseStatusException(CONFLICT, "Request is not awaiting review");
    String note = input.reviewNote() == null ? null : input.reviewNote().trim();
    if (input.action() != ReviewHorseRegistrationRequest.Action.APPROVE
        && (note == null || note.isBlank()))
      throw new ResponseStatusException(BAD_REQUEST, "reviewNote is required for this action");
    if (input.action() != ReviewHorseRegistrationRequest.Action.APPROVE
        && (input.horseId() != null || input.stableBoxId() != null))
      throw new ResponseStatusException(
          BAD_REQUEST, "horseId and stableBoxId are only valid for APPROVE");
    String horse = null;
    String status =
        switch (input.action()) {
          case APPROVE -> {
            horse = approve(row, input, manager);
            yield "APPROVED";
          }
          case REQUEST_INFORMATION -> "NEEDS_INFORMATION";
          case REJECT -> "REJECTED";
        };
    db.update(
        "UPDATE horse_registration_requests SET"
            + " status=?,approved_horse_id=?,reviewed_by=?,review_note=?,reviewed_at=? WHERE id=?",
        status,
        horse,
        manager,
        note,
        time.utcNow(),
        id);
    db.update(
        "INSERT INTO"
            + " horse_registration_history(id,request_id,actor_id,action,review_note,from_status,to_status,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        manager,
        input.action().name(),
        note,
        old,
        status,
        time.utcNow());
    db.update(
        "INSERT INTO audit_logs(id,user_id,action_performed,created_at) VALUES (?,?,?,?)",
        UUID.randomUUID().toString(),
        manager,
        "REVIEW_HORSE_REGISTRATION: " + id + " " + input.action(),
        time.utcNow());
    notifications.schedule(
        ((Number) row.get("owner_id")).longValue(),
        "horse_registration_requests",
        id,
        "Registration updated",
        time.now());
    row.put("status", status);
    row.put("approved_horse_id", horse);
    row.put("reviewed_by", manager);
    row.put("review_note", note);
    row.put("reviewed_at", time.utcNow().toInstant(java.time.ZoneOffset.UTC));
    return project(row);
  }

  private String approve(
      Map<String, Object> row, ReviewHorseRegistrationRequest input, long manager) {
    long owner = ((Number) row.get("owner_id")).longValue();
    if (db.queryForObject(
            "SELECT count(*) FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=? AND"
                + " u.status='APPROVED' AND u.deleted_at IS NULL AND r.role_name='HORSE_OWNER'",
            Long.class,
            owner)
        == 0)
      throw new ResponseStatusException(
          CONFLICT, "Requester must still be an active approved Horse Owner");
    boolean link = "LINK".equals(row.get("request_type"));
    String horse;
    Object previousOwner = null;
    String stable;
    if (link) {
      if (input.horseId() == null)
        throw new ResponseStatusException(BAD_REQUEST, "horseId is required for LINK approval");
      horse = input.horseId().toString();
      var existing =
          db.query(
              "SELECT * FROM horses WHERE id=? AND deleted_at IS NULL FOR UPDATE",
              VetRows.MAPPER,
              horse);
      if (existing.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Horse not found");
      var target = existing.getFirst();
      previousOwner = target.get("owner_id");
      if (previousOwner instanceof Number n && n.longValue() != owner)
        throw new ResponseStatusException(
            CONFLICT, "Horse belongs to another owner; use the ownership transfer workflow");
      stable =
          input.stableBoxId() == null
              ? (String) target.get("stable_box_id")
              : input.stableBoxId().toString();
    } else {
      if (input.horseId() != null)
        throw new ResponseStatusException(BAD_REQUEST, "horseId is only valid for LINK approval");
      horse = UUID.randomUUID().toString();
      stable = input.stableBoxId() == null ? null : input.stableBoxId().toString();
    }
    if (stable == null) throw new ResponseStatusException(BAD_REQUEST, "stableBoxId is required");
    var boxes =
        db.query("SELECT * FROM stable_boxes WHERE id=? FOR UPDATE", VetRows.MAPPER, stable);
    if (boxes.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Stable box not found");
    var box = boxes.getFirst();
    long occupied =
        db.queryForObject(
            "SELECT count(*) FROM horses WHERE stable_box_id=? AND deleted_at IS NULL AND id<>?",
            Long.class,
            stable,
            horse);
    if (!"Available".equals(box.get("status"))
        || !(box.get("capacity") instanceof Number cap)
        || occupied >= cap.intValue())
      throw new ResponseStatusException(CONFLICT, "Stable box is unavailable or full");
    if (link) {
      // A link verifies ownership; it does not overwrite an existing horse's clinical/profile data.
      db.update("UPDATE horses SET owner_id=?,stable_box_id=? WHERE id=?", owner, stable, horse);
    } else {
      db.update(
          "INSERT INTO"
              + " horses(id,horse_name,breed,birth_year,height_cm,current_weight_kg,pedigree_father,pedigree_mother,microchip_number,passport_number,image_url,owner_id,stable_box_id,created_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          horse,
          row.get("horse_name"),
          row.get("breed"),
          row.get("birth_year"),
          row.get("height_cm"),
          row.get("current_weight_kg"),
          row.get("pedigree_father"),
          row.get("pedigree_mother"),
          row.get("microchip_number"),
          row.get("passport_number"),
          row.get("horse_image_path"),
          owner,
          stable,
          time.utcNow());
    }
    if (previousOwner == null)
      db.update(
          "INSERT INTO"
              + " horse_ownership_history(id,horse_id,previous_owner_id,new_owner_id,changed_by,change_reason,changed_at)"
              + " VALUES (?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          horse,
          previousOwner,
          owner,
          manager,
          link ? "LINK_APPROVED" : "REGISTRATION_APPROVED",
          time.utcNow());
    return horse;
  }
}
