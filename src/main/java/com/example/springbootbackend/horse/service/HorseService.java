package com.example.springbootbackend.horse.service;

import com.example.springbootbackend.horse.dto.request.HorseRequest;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.example.springbootbackend.veterinarian.support.VetTime;
import static org.springframework.http.HttpStatus.*;

@Service
public class HorseService {
    private final JdbcTemplate db;
    private final VetTime time;
    private final int groomAssignmentWindowDays;
    public HorseService(JdbcTemplate db, VetTime time,
            @Value("${app.groom.assignment-window-days:14}") int groomAssignmentWindowDays) {
        this.db=db;
        this.time=time;
        this.groomAssignmentWindowDays=Math.max(0,groomAssignmentWindowDays);
    }

    public record Actor(long id, String role) {}
    public Actor actor(String email, boolean write) {
        var rows=db.queryForList("SELECT u.user_id,u.must_change_password,r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id WHERE lower(u.email)=lower(?) AND u.status='APPROVED' AND u.deleted_at IS NULL", email);
        if(rows.isEmpty()) throw new ResponseStatusException(FORBIDDEN,"Tài khoản chưa được duyệt");
        var row=rows.getFirst();
        var actor=new Actor(((Number)row.get("user_id")).longValue(),(String)row.get("role_name"));
        if(Boolean.TRUE.equals(row.get("must_change_password"))) throw new ResponseStatusException(FORBIDDEN,"Change password before using business APIs");
        if(write ? !actor.role().equals("CLUB_MANAGER") : !Set.of("CLUB_MANAGER","HEAD_TRAINER","VETERINARIAN","GROOM","HORSE_OWNER").contains(actor.role()))
            throw new ResponseStatusException(FORBIDDEN,"Không có quyền truy cập");
        return actor;
    }

    public Map<String,Object> list(Actor actor,String search,String breed,String status,UUID stable,Boolean locked,int page,int size) {
        if(page<0 || size<1 || size>100) throw new ResponseStatusException(BAD_REQUEST,"Phân trang không hợp lệ");
        if(actor.role().equals("HORSE_OWNER") && (search!=null || breed!=null || status!=null || stable!=null || locked!=null))
            throw new ResponseStatusException(FORBIDDEN,"Bộ lọc chỉ dành cho quản lý và huấn luyện viên");
        StringBuilder where=new StringBuilder(" WHERE h.deleted_at IS NULL");
        List<Object> args=new ArrayList<>();
        if(actor.role().equals("HORSE_OWNER")) { where.append(" AND h.owner_id=?"); args.add(actor.id()); }
        if(actor.role().equals("GROOM")) {
            LocalDate today=time.today();
            LocalDate from=today.minusDays(groomAssignmentWindowDays);
            LocalDate through=today.plusDays(groomAssignmentWindowDays);
            where.append(" AND (EXISTS (SELECT 1 FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                    +"WHERE ts.horse_id=h.id AND ts.assigned_groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?) "
                    +"OR EXISTS (SELECT 1 FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                    +"WHERE t.horse_id=h.id AND t.groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?))");
            args.add(actor.id()); args.add(from); args.add(through);
            args.add(actor.id()); args.add(from); args.add(through);
        }
        if(search!=null && !search.isBlank()) { where.append(" AND lower(h.horse_name) LIKE ? ESCAPE '!'"); args.add("%"+search.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%"); }
        if(breed!=null) { where.append(" AND h.breed=?"); args.add(breed); }
        if(status!=null) { where.append(" AND h.current_status=?"); args.add(status); }
        if(stable!=null) { where.append(" AND h.stable_box_id=?"); args.add(stable.toString()); }
        if(locked!=null) { where.append(" AND h.is_training_locked=?"); args.add(locked); }
        long total=db.queryForObject("SELECT count(*) FROM horses h"+where,Long.class,args.toArray());
        args.add(size); args.add((long)page*size);
        String latestMetricWeight="(SELECT m.body_weight_kg FROM shared_training_metrics m WHERE m.horse_id=h.id "
                +"AND m.body_weight_kg IS NOT NULL ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1) AS latest_metric_weight_kg";
        String columns = actor.role().equals("GROOM")
            ? "h.id,h.horse_name,h.image_url,h.stable_box_id,h.current_status,h.height_cm,h.current_weight_kg,s.box_code,s.section," + latestMetricWeight
            : "h.*,s.box_code,s.section,u.full_name AS owner_name," + latestMetricWeight;
        var items = db.queryForList("SELECT " + columns + " FROM horses h LEFT JOIN stable_boxes s ON s.id=h.stable_box_id LEFT JOIN users u ON u.user_id=h.owner_id"+where+" ORDER BY h.created_at DESC,h.id LIMIT ? OFFSET ?",args.toArray());
        items=items.stream().map(row -> {
            Object latestWeight=row.remove("latest_metric_weight_kg");
            if(latestWeight!=null) row.put("current_weight_kg",latestWeight);
            return actor.role().equals("GROOM") ? groomHorseSummary(row) : row;
        }).toList();
        return Map.of("items",items,"data",items,"total",total,"page",page,"size",size,"limit",size);
    }

    public Map<String,Object> detail(Actor actor,UUID id) {
        if(actor.role().equals("GROOM")) {
            if(!groomHasAssignment(actor.id(),id.toString()))
                throw new ResponseStatusException(FORBIDDEN,"Horse is not assigned to this Groom");
            var assigned=db.queryForList("SELECT h.id,h.horse_name,h.image_url,h.stable_box_id,h.current_status,h.height_cm,h.current_weight_kg,"
                    +"s.box_code,s.section,(SELECT m.body_weight_kg FROM shared_training_metrics m WHERE m.horse_id=h.id AND m.body_weight_kg IS NOT NULL "
                    +"ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1) AS latest_metric_weight_kg FROM horses h LEFT JOIN stable_boxes s ON s.id=h.stable_box_id "
                    +"WHERE h.id=? AND h.deleted_at IS NULL",id.toString());
            if(assigned.isEmpty()) throw new ResponseStatusException(FORBIDDEN,"Horse is not assigned to this Groom");
            return groomHorseSummary(assigned.getFirst());
        }
        var rows=db.queryForList("SELECT h.*,s.box_code,s.section,u.full_name AS owner_name,"
                +"(SELECT m.body_weight_kg FROM shared_training_metrics m WHERE m.horse_id=h.id AND m.body_weight_kg IS NOT NULL "
                +"ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1) AS latest_metric_weight_kg "
                +"FROM horses h LEFT JOIN stable_boxes s ON s.id=h.stable_box_id LEFT JOIN users u ON u.user_id=h.owner_id "
                +"WHERE h.id=? AND h.deleted_at IS NULL",id.toString());
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Không tìm thấy ngựa");
        var horse=rows.getFirst();
        Object latestWeight=horse.remove("latest_metric_weight_kg");
        if(latestWeight!=null) horse.put("current_weight_kg",latestWeight);
        if(actor.role().equals("HORSE_OWNER") && (!(horse.get("owner_id") instanceof Number owner) || owner.longValue()!=actor.id()))
            throw new ResponseStatusException(FORBIDDEN,"Ngựa không thuộc sở hữu của bạn");
        horse.put("stable_box", Map.of("id", horse.get("stable_box_id"), "box_code", horse.get("box_code")));
        horse.put("owner", horse.get("owner_id") == null ? null : Map.of("id", horse.get("owner_id"), "full_name", horse.get("owner_name")));
        return horse;
    }

    private boolean groomHasAssignment(long groomId,String horseId) {
        LocalDate today=time.today();
        LocalDate from=today.minusDays(groomAssignmentWindowDays);
        LocalDate through=today.plusDays(groomAssignmentWindowDays);
        Long count=db.queryForObject("SELECT count(*) FROM horses h WHERE h.id=? AND h.deleted_at IS NULL AND ("
                +"EXISTS (SELECT 1 FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                +"WHERE ts.horse_id=h.id AND ts.assigned_groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?) OR "
                +"EXISTS (SELECT 1 FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                +"WHERE t.horse_id=h.id AND t.groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') AND ce.event_date BETWEEN ? AND ?))",
                Long.class,horseId,groomId,from,through,groomId,from,through);
        return count!=null && count>0;
    }

    private Map<String,Object> groomHorseSummary(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",row.get("id"));
        result.put("horse_name",row.get("horse_name"));
        result.put("image_url",row.get("image_url"));
        if(row.get("stable_box_id")==null) result.put("stable_box",null);
        else {
            Map<String,Object> stable=new LinkedHashMap<>();
            stable.put("box_code",row.get("box_code"));
            stable.put("section",row.get("section"));
            result.put("stable_box",stable);
        }
        result.put("current_status",row.get("current_status"));
        result.put("height_cm",row.get("height_cm"));
        result.put("current_weight_kg",row.get("latest_metric_weight_kg") == null ? row.get("current_weight_kg") : row.get("latest_metric_weight_kg"));
        return result;
    }

    @Transactional
    public Map<String,Object> save(Actor actor,UUID id,HorseRequest request) {
        Map<String,Object> old=null;
        if(id!=null) {
            var rows=db.queryForList("SELECT * FROM horses WHERE id=? AND deleted_at IS NULL FOR UPDATE",id.toString());
            if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Không tìm thấy ngựa");
            old=rows.getFirst();
            Long owner=old.get("owner_id")==null?null:((Number)old.get("owner_id")).longValue();
            if(!Objects.equals(owner,request.ownerId()) && !request.confirmOwnerChange())
                throw new ResponseStatusException(CONFLICT,"Cần xác nhận thay đổi chủ sở hữu");
        }
        if(request.birthYear()!=null && request.birthYear()>time.today().getYear()) throw new ResponseStatusException(BAD_REQUEST,"Năm sinh không được ở tương lai");
        if(request.ownerId()!=null && db.queryForObject("SELECT count(*) FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=? AND r.role_name='HORSE_OWNER' AND u.status='APPROVED' AND u.deleted_at IS NULL",Long.class,request.ownerId())==0)
            throw new ResponseStatusException(BAD_REQUEST,"Chủ ngựa phải là Horse Owner đã được duyệt");
        // Serialize competing admissions to the same stable; never rely on a dropdown alone.
        var boxes=db.queryForList("SELECT * FROM stable_boxes WHERE id=? FOR UPDATE",request.stableBoxId().toString());
        if(boxes.isEmpty()) throw new ResponseStatusException(BAD_REQUEST,"Chuồng không tồn tại");
        var box=boxes.getFirst();
        long occupied=db.queryForObject("SELECT count(*) FROM horses WHERE stable_box_id=? AND deleted_at IS NULL AND id<>?",Long.class,request.stableBoxId().toString(),id==null?"":id.toString());
        if(!"Available".equals(box.get("status")) || !(box.get("capacity") instanceof Number capacity) || occupied>=capacity.intValue())
            throw new ResponseStatusException(CONFLICT,"Chuồng đã đầy hoặc không khả dụng");
        String path=request.imagePath();
        if(path==null && old!=null) path=(String)old.get("image_url");
        if(path!=null && !Objects.equals(path,old==null?null:old.get("image_url"))) {
            if(db.queryForObject("SELECT count(*) FROM horse_image_uploads WHERE object_path=? AND uploaded_by=?",Long.class,path,actor.id())==0)
                throw new ResponseStatusException(BAD_REQUEST,"Ảnh phải được upload qua hệ thống bởi tài khoản hiện tại");
        }
        if(id==null) {
            id=UUID.randomUUID();
            db.update("INSERT INTO horses(id,horse_name,breed,birth_year,height_cm,pedigree_father,pedigree_mother,image_url,stable_box_id,owner_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",id.toString(),request.horseName().trim(),request.breed(),request.birthYear(),request.heightCm(),request.pedigreeFather(),request.pedigreeMother(),path,request.stableBoxId().toString(),request.ownerId(),time.utcNow());
            audit(actor.id(), "CREATE_HORSE: " + id);
        } else {
            db.update("UPDATE horses SET horse_name=?,breed=?,birth_year=?,height_cm=?,pedigree_father=?,pedigree_mother=?,image_url=?,stable_box_id=?,owner_id=? WHERE id=?",request.horseName().trim(),request.breed(),request.birthYear(),request.heightCm(),request.pedigreeFather(),request.pedigreeMother(),path,request.stableBoxId().toString(),request.ownerId(),id.toString());
            audit(actor.id(), "UPDATE_HORSE: " + id);
            if (!Objects.equals(old.get("owner_id"), request.ownerId())) audit(actor.id(), "CHANGE_HORSE_OWNER: " + id);
        }
        return detail(actor,id);
    }

    @Transactional
    public Map<String,Object> update(Actor actor, UUID id, com.example.springbootbackend.horse.dto.request.HorseUpdateRequest request) {
        var rows = db.queryForList("SELECT * FROM horses WHERE id=? AND deleted_at IS NULL FOR UPDATE", id.toString());
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy ngựa");
        var old = rows.getFirst();
        String horseName = request.horseName().trim();
        String breed = request.breed();
        Integer birthYear = request.birthYear();
        var heightCm = request.heightCm();
        String father = request.pedigreeFather();
        String mother = request.pedigreeMother();
        String stable = request.stableBoxId().toString();
        Long owner = request.ownerId();
        String image = request.imagePath();
        var merged = new HorseRequest(horseName, breed, birthYear, heightCm, father, mother, image, UUID.fromString(stable), owner, request.confirmOwnerChange());
        return save(actor, id, merged);
    }
    public List<Map<String,Object>> stables() {
        return db.queryForList("SELECT s.*, (SELECT count(*) FROM horses h WHERE h.stable_box_id=s.id AND h.deleted_at IS NULL) AS occupied FROM stable_boxes s WHERE s.status='Available' AND s.capacity > (SELECT count(*) FROM horses h WHERE h.stable_box_id=s.id AND h.deleted_at IS NULL) ORDER BY s.box_code");
    }
    public Map<String,Object> owners(String search,int page,int size) {
        if(page<0 || size<1 || size>50) throw new ResponseStatusException(BAD_REQUEST,"Phân trang chủ sở hữu không hợp lệ");
        if(search==null || search.isBlank()) return ownerPage(List.of(),false,page,size);

        String term=search.trim();
        String idTerm=term.startsWith("#") ? term.substring(1) : term;
        Long exactId=null;
        if(idTerm.matches("\\d+")) {
            try { exactId=Long.parseLong(idTerm); }
            catch(NumberFormatException ignored) { /* Keep searching the text fields. */ }
        }
        boolean searchByIdOnly=exactId!=null && idTerm.length()<3;
        if(term.length()<3 && !searchByIdOnly) return ownerPage(List.of(),false,page,size);

        String sql="SELECT u.user_id,u.full_name,u.email,u.phone "
            +"FROM users u JOIN roles r ON r.role_id=u.role_id "
            +"WHERE r.role_name='HORSE_OWNER' AND u.status='APPROVED' AND u.deleted_at IS NULL AND ";
        List<Object> args=new ArrayList<>();
        if(searchByIdOnly) {
            sql+="u.user_id=? ";
            args.add(exactId);
        } else {
            String textTerm=exactId==null ? term : idTerm;
            String pattern="%"+escapeLike(textTerm.toLowerCase(Locale.ROOT))+"%";
            sql+="(lower(u.full_name) LIKE ? ESCAPE '!' "
                +"OR lower(u.email) LIKE ? ESCAPE '!' "
                +"OR lower(COALESCE(u.phone,'')) LIKE ? ESCAPE '!'";
            args.add(pattern); args.add(pattern); args.add(pattern);
            if(exactId!=null) { sql+=" OR u.user_id=?"; args.add(exactId); }
            sql+=") ";
        }
        sql+="ORDER BY u.full_name,u.user_id LIMIT ? OFFSET ?";
        args.add(size+1); args.add((long)page*size);
        List<Map<String,Object>> rows=db.queryForList(sql,args.toArray());
        boolean hasMore=rows.size()>size;
        List<Map<String,Object>> items=hasMore ? rows.subList(0,size) : rows;
        return ownerPage(items,hasMore,page,size);
    }

    private Map<String,Object> ownerPage(List<Map<String,Object>> items,boolean hasMore,int page,int size) {
        return Map.of("items",items,"hasMore",hasMore,"page",page,"size",size);
    }

    private String escapeLike(String value) {
        return value.replace("!","!!").replace("%","!%").replace("_","!_");
    }
    public Map<String,Object> warnings(Actor actor,UUID id) {
        detail(actor,id);
        return Map.of("scheduledTraining",db.queryForObject("SELECT count(*) FROM training_schedules WHERE horse_id=? AND status IN ('Scheduled','InProgress')",Long.class,id.toString()),
            "medicalRecords",db.queryForObject("SELECT count(*) FROM medical_records WHERE horse_id=?",Long.class,id.toString()),
            "activePrescriptions",db.queryForObject("SELECT count(*) FROM prescriptions WHERE horse_id=? AND status='Active'",Long.class,id.toString()));
    }
    @Transactional
    public Map<String,Object> delete(Actor actor,UUID id,boolean confirmed) {
        if(!confirmed) throw new ResponseStatusException(BAD_REQUEST,"Cần xác nhận xóa hồ sơ");
        detail(actor,id);
        long scheduled = db.queryForObject("SELECT count(*) FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id WHERE ts.horse_id=? AND ts.status='Scheduled' AND ce.event_date>=?", Long.class, id.toString(), time.today());
        LocalDateTime deletedAt=time.utcNow();
        if(db.update("UPDATE horses SET deleted_at=? WHERE id=? AND deleted_at IS NULL",deletedAt,id.toString())==0)
            throw new ResponseStatusException(NOT_FOUND,"Không tìm thấy ngựa");
        audit(actor.id(), "DELETE_HORSE: " + id);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("deleted_at", deletedAt);
        if (scheduled > 0) result.put("warning", "Ngựa đang có " + scheduled + " lịch tập sắp tới");
        return result;
    }

    private void audit(long actorId, String action) {
        db.update("INSERT INTO audit_logs(id,user_id,action_performed,created_at) VALUES (?,?,?,?)", UUID.randomUUID().toString(), actorId, action, time.utcNow());
    }
}
