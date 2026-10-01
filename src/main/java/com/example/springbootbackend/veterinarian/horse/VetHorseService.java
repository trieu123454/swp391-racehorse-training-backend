package com.example.springbootbackend.veterinarian.horse;

import com.example.springbootbackend.veterinarian.support.*;
import com.example.springbootbackend.veterinarian.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class VetHorseService {
    private final VetData data;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final NotificationService notifications;
    public VetHorseService(VetData data,VetAccess access,VetAudit audit,VetTime time,NotificationService notifications) {
        this.data=data; this.access=access; this.audit=audit; this.time=time; this.notifications=notifications;
    }
    public Map<String,Object> overview(String email,String section) {
        access.requireRole(email, "VETERINARIAN");
        String query="SELECT h.id,h.horse_name,h.image_url,h.current_status,h.readiness_status,h.is_training_locked,h.lock_level,h.lock_reason,"
                +"s.id AS box_id,s.box_code,s.section FROM horses h LEFT JOIN stable_boxes s ON s.id=h.stable_box_id WHERE h.deleted_at IS NULL";
        var args=new ArrayList<Object>();
        if(section!=null) { query+=" AND s.section=?"; args.add(section); }
        query+=" ORDER BY CASE h.current_status WHEN 'Injured' THEN 1 WHEN 'Quarantine' THEN 2 WHEN 'Monitoring' THEN 3 ELSE 4 END,h.horse_name,h.id";
        var rows=data.db.query(query,VetRows.MAPPER,args.toArray());
        Map<String,Long> counts=new LinkedHashMap<>();
        for(String status:List.of("Healthy","Monitoring","Injured","Quarantine")) counts.put(status,0L);
        for(var row:rows) {
            String status=row.get("current_status").toString(); counts.computeIfPresent(status,(key,value)->value+1);
            Object box=row.remove("box_id"), code=row.remove("box_code"), boxSection=row.remove("section");
            Map<String,Object> stable=new LinkedHashMap<>(); stable.put("id",box); stable.put("box_code",code); stable.put("section",boxSection);
            row.put("stable_box",box==null?null:stable);
        }
        return Map.of("counts",counts,"horses",rows);
    }
    public Map<String,Object> incidents(String email,String requestedStatus) {
        access.requireRole(email, "VETERINARIAN");
        String status=requestedStatus==null||requestedStatus.isBlank()?"Pending":requestedStatus.trim();
        if(!List.of("Pending","Resolved").contains(status))
            throw new VetException(400,"INVALID_STATUS","Status must be Pending or Resolved");
        var rows=data.db.query("SELECT i.id,i.horse_id,h.horse_name,h.current_status,h.readiness_status,"
                +"s.box_code,s.section,i.groom_id,g.full_name AS groom_name,i.issue_description,i.image_url,"
                +"i.status,i.created_at,i.resolved_at FROM stable_incidents i JOIN horses h ON h.id=i.horse_id "
                +"LEFT JOIN stable_boxes s ON s.id=h.stable_box_id LEFT JOIN users g ON g.user_id=i.groom_id "
                +"WHERE i.status=? ORDER BY i.created_at DESC,i.id",VetRows.MAPPER,status);
        return Map.of("status",status,"data",rows);
    }
    @Transactional
    public Map<String,Object> status(String email,String ip,String horse,JsonNode body) {
        long actor=access.requireRole(email, "VETERINARIAN"); var old=horse(horse,true);
        var fields=VetInput.parse(body,"current_status:20","readiness_status:20","note:0");
        VetInput.choice(fields,"current_status","Healthy","Monitoring","Injured","Quarantine");
        String status=fields.get("current_status").toString();
        if (fields.containsKey("readiness_status"))
            VetInput.choice(fields,"readiness_status","Ready","NotReady","Unknown");
        boolean medicallyRestricted=List.of("Injured","Quarantine").contains(status);
        if (medicallyRestricted && "Ready".equals(fields.get("readiness_status")))
            throw VetException.invalid("readiness_status","Ngua Injured/Quarantine khong the Ready");
        if (hasUnresolvedInjury(horse) && ("Healthy".equals(status)
                || "Ready".equals(fields.getOrDefault("readiness_status",old.get("readiness_status")))))
            throw new VetException(409,"ACTIVE_INJURY_MARKER","Close the latest injury assessment as Recovered before marking the horse Healthy or Ready");
        boolean newlyLocked=medicallyRestricted && !Boolean.TRUE.equals(old.get("is_training_locked"));
        Map<String,Object> updates=new LinkedHashMap<>();
        updates.put("current_status",status);
        if (fields.containsKey("readiness_status")) updates.put("readiness_status",fields.get("readiness_status"));
        else if (medicallyRestricted && "Ready".equals(old.get("readiness_status"))) updates.put("readiness_status","NotReady");
        if (newlyLocked) {
            updates.put("is_training_locked",true);
            updates.put("lock_level","Critical");
            updates.put("lock_reason","Medical status: "+status);
        }
        data.update("horses",horse,updates);
        if (newlyLocked) {
            blockUpcomingSessions(horse);
            notifyTrainingStaff(horse,"Ngua "+old.get("horse_name")+" bi tu dong khoa huan luyen do trang thai "+status);
        }
        audit.record(actor,ip,"UPDATE_HEALTH_STATUS:"+horse+":"+old.get("current_status")+"->"+status
                +(fields.get("note")==null?"":":"+fields.get("note")));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",horse); result.put("current_status",status);
        result.put("readiness_status",updates.getOrDefault("readiness_status",old.get("readiness_status")));
        result.put("is_training_locked",newlyLocked || Boolean.TRUE.equals(old.get("is_training_locked")));
        result.put("lock_level",newlyLocked?"Critical":old.get("lock_level"));
        result.put("lock_reason",newlyLocked?updates.get("lock_reason"):old.get("lock_reason"));
        result.put("suggest_lock",false);
        result.put("upcoming_sessions",data.db.query("SELECT ts.id AS training_schedule_id,ce.event_date,ce.start_time,ts.session_type "
                +"FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id WHERE ts.horse_id=? "
                +"AND ce.status IN ('Scheduled','Blocked') AND ce.event_date>=? AND ts.session_type IN ('Training','TrialRun') "
                +"ORDER BY ce.event_date,ce.start_time,ce.id",VetRows.MAPPER,horse,time.today()));
        return result;
    }
    @Transactional
    public Map<String,Object> lock(String email,String ip,String horse,JsonNode body) {
        long actor=access.requireRole(email, "VETERINARIAN"); var old=horse(horse,true);
        var fields=VetInput.parse(body,"lock_level:20","lock_reason:255");
        VetInput.choice(fields,"lock_level","Warning","Critical"); VetInput.required(fields,"lock_reason");
        boolean wasLocked=Boolean.TRUE.equals(old.get("is_training_locked"));
        boolean changed=!wasLocked || !Objects.equals(old.get("lock_level"),fields.get("lock_level")) || !Objects.equals(old.get("lock_reason"),fields.get("lock_reason"));
        fields.put("is_training_locked",true); data.update("horses",horse,fields);
        blockUpcomingSessions(horse);
        if(changed) notifyTrainingStaff(horse,"Ngựa "+old.get("horse_name")+" bị khóa huấn luyện ("+fields.get("lock_level")+"): "+fields.get("lock_reason"));
        audit.record(actor,ip,"LOCK_TRAINING:"+horse+":"+fields.get("lock_level"));
        var result=new LinkedHashMap<>(fields); result.put("id",horse); result.put("was_locked",wasLocked);
        result.put("upcoming_sessions",data.db.query("SELECT ts.id AS training_schedule_id,ce.event_date,ce.start_time,ts.session_type "
                +"FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id WHERE ts.horse_id=? "
                +"AND ce.status IN ('Scheduled','Blocked') AND ce.event_date>=? AND ce.event_type IN ('Training','TrialRun') ORDER BY ce.event_date,ce.start_time,ce.id",VetRows.MAPPER,horse,time.today()));
        return result;
    }
    @Transactional
    public Map<String,Object> unlock(String email,String ip,String horse,JsonNode body) {
        long actor=access.requireRole(email, "VETERINARIAN"); var old=horse(horse,true);
        var fields=VetInput.parse(body,"reason:0"); VetInput.required(fields,"reason");
        if(!Boolean.TRUE.equals(old.get("is_training_locked"))) throw new VetException(409,"NOT_LOCKED","Ngựa chưa bị khóa huấn luyện");
        if(List.of("Injured","Quarantine").contains(old.get("current_status")))
            throw new VetException(409,"MEDICAL_RESTRICTION","Cần cập nhật ngựa hết Injured/Quarantine trước khi mở khóa huấn luyện");
        if(hasUnresolvedInjury(horse))
            throw new VetException(409,"ACTIVE_INJURY_MARKER","Close the latest injury assessment as Recovered before unlocking training");
        data.db.update("UPDATE horses SET is_training_locked=FALSE,lock_level=NULL,lock_reason=NULL WHERE id=?",horse);
        data.db.update("UPDATE training_schedules SET status='Scheduled' WHERE horse_id=? AND status='Blocked' AND id IN "
                +"(SELECT ts.id FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                +"WHERE ts.horse_id=? AND ts.status='Blocked' AND ce.event_date>=? AND ts.session_type IN ('Training','TrialRun'))",
                horse,horse,time.today());
        data.db.update("UPDATE calendar_events ce SET status='Scheduled' FROM training_schedules ts "
                +"WHERE ts.calendar_event_id=ce.id AND ts.horse_id=? AND ts.status='Scheduled' AND ce.status='Blocked' "
                +"AND ce.event_date>=? AND ts.session_type IN ('Training','TrialRun')",horse,time.today());
        notifyTrainingStaff(horse,"Ngựa "+old.get("horse_name")+" đã được mở khóa huấn luyện");
        audit.record(actor,ip,"UNLOCK_TRAINING:"+horse+":"+fields.get("reason"));
        return Map.of("id",horse,"is_training_locked",false,"suggest_status_update",List.of("Injured","Quarantine").contains(old.get("current_status")));
    }

    /** Flow 2 must call inside its write transaction BEFORE creating plans/events. Holds horse row lock. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void assertHorseNotLocked(String horse) {
        var row=horse(horse,true);
        if(hasUnresolvedInjury(horse))
            throw new VetException(409,"ACTIVE_INJURY_MARKER","Horse has an injury assessment that is still Active or Recovering");
        if(Boolean.TRUE.equals(row.get("is_training_locked"))) {
            Map<String,Object> details=new LinkedHashMap<>(); details.put("lock_level",row.get("lock_level")); details.put("lock_reason",row.get("lock_reason"));
            throw new VetException(409,"TRAINING_LOCKED","Ngựa đang bị khóa huấn luyện",details);
        }
    }
    private Map<String,Object> horse(String id,boolean lock) {
        access.requireHorse(id,lock); return data.db.query("SELECT * FROM horses WHERE id=?",VetRows.MAPPER,id).getFirst();
    }
    private boolean hasUnresolvedInjury(String horse) {
        Long count=data.db.queryForObject("SELECT count(*) FROM (SELECT recovery_status,"
                +"ROW_NUMBER() OVER(PARTITION BY lower(trim(body_part)) ORDER BY marked_at DESC,id DESC) AS row_num "
                +"FROM injury_markers WHERE horse_id=?) latest WHERE row_num=1 AND recovery_status IN ('Active','Recovering')",
                Long.class,horse);
        return count!=null && count>0;
    }
    private void notifyTrainingStaff(String horse,String message) {
        var recipients=data.db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                +"WHERE u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL AND r.role_name='HEAD_TRAINER'",Long.class);
        for(long recipient:recipients) notifications.schedule(recipient,"Horses",horse,message,time.now());
        var grooms=data.db.queryForList("SELECT DISTINCT u.user_id FROM training_schedules ts "
                +"JOIN calendar_events ce ON ce.id=ts.calendar_event_id JOIN users u ON u.user_id=ts.assigned_groom_id "
                +"JOIN roles r ON r.role_id=u.role_id WHERE ts.horse_id=? AND ts.session_type IN ('Training','TrialRun') "
                +"AND ce.event_date>=? AND ce.status IN ('Scheduled','Blocked') AND r.role_name='GROOM' "
                +"AND u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL ORDER BY u.user_id",
                Long.class,horse,time.today());
        for(long groom:grooms) notifications.schedule(groom,"Horses",horse,message,time.now());
    }
    private void blockUpcomingSessions(String horse) {
        data.db.update("UPDATE training_schedules ts SET status='Blocked' FROM calendar_events ce "
                +"WHERE ce.id=ts.calendar_event_id AND ts.horse_id=? AND ts.status='Scheduled' "
                +"AND ce.status='Scheduled' AND ce.event_date>=? AND ts.session_type IN ('Training','TrialRun')",
                horse,time.today());
        data.db.update("UPDATE calendar_events ce SET status='Blocked' FROM training_schedules ts "
                +"WHERE ts.calendar_event_id=ce.id AND ts.horse_id=? AND ts.status='Blocked' "
                +"AND ce.status='Scheduled' AND ce.event_date>=? AND ts.session_type IN ('Training','TrialRun')",
                horse,time.today());
    }
}
