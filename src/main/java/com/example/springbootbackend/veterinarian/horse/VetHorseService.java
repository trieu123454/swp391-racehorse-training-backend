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
        access.requireUser(email,true);
        String query="SELECT h.id,h.horse_name,h.image_url,h.current_status,h.is_training_locked,h.lock_level,h.lock_reason,"
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
    @Transactional
    public Map<String,Object> status(String email,String ip,String horse,JsonNode body) {
        long actor=access.requireUser(email,true); var old=horse(horse,true);
        var fields=VetInput.parse(body,"current_status:20","note:0");
        VetInput.choice(fields,"current_status","Healthy","Monitoring","Injured","Quarantine");
        String status=fields.get("current_status").toString();
        data.update("horses",horse,Map.of("current_status",status));
        audit.record(actor,ip,"UPDATE_HEALTH_STATUS:"+horse+":"+old.get("current_status")+"->"+status
                +(fields.get("note")==null?"":":"+fields.get("note")));
        return Map.of("id",horse,"current_status",status,"suggest_lock",List.of("Injured","Quarantine").contains(status) && !Boolean.TRUE.equals(old.get("is_training_locked")));
    }
    @Transactional
    public Map<String,Object> lock(String email,String ip,String horse,JsonNode body) {
        long actor=access.requireUser(email,true); var old=horse(horse,true);
        var fields=VetInput.parse(body,"lock_level:20","lock_reason:255");
        VetInput.choice(fields,"lock_level","Warning","Critical"); VetInput.required(fields,"lock_reason");
        boolean wasLocked=Boolean.TRUE.equals(old.get("is_training_locked"));
        boolean changed=!wasLocked || !Objects.equals(old.get("lock_level"),fields.get("lock_level")) || !Objects.equals(old.get("lock_reason"),fields.get("lock_reason"));
        fields.put("is_training_locked",true); data.update("horses",horse,fields);
        if(changed) notifyTrainers(horse,"Ngựa "+old.get("horse_name")+" bị khóa huấn luyện ("+fields.get("lock_level")+"): "+fields.get("lock_reason"));
        audit.record(actor,ip,"LOCK_TRAINING:"+horse+":"+fields.get("lock_level"));
        var result=new LinkedHashMap<>(fields); result.put("id",horse); result.put("was_locked",wasLocked);
        result.put("upcoming_sessions",data.db.query("SELECT ts.id AS training_schedule_id,ce.event_date,ce.start_time,ts.session_type "
                +"FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id WHERE ts.horse_id=? "
                +"AND ce.status='Scheduled' AND ce.event_date>=? AND ce.event_type IN ('Training','TrialRun') ORDER BY ce.event_date,ce.start_time,ce.id",VetRows.MAPPER,horse,time.today()));
        return result;
    }
    @Transactional
    public Map<String,Object> unlock(String email,String ip,String horse,JsonNode body) {
        long actor=access.requireUser(email,true); var old=horse(horse,true);
        var fields=VetInput.parse(body,"reason:0"); VetInput.required(fields,"reason");
        if(!Boolean.TRUE.equals(old.get("is_training_locked"))) throw new VetException(409,"NOT_LOCKED","Ngựa chưa bị khóa huấn luyện");
        data.db.update("UPDATE horses SET is_training_locked=FALSE,lock_level=NULL,lock_reason=NULL WHERE id=?",horse);
        notifyTrainers(horse,"Ngựa "+old.get("horse_name")+" đã được mở khóa huấn luyện");
        audit.record(actor,ip,"UNLOCK_TRAINING:"+horse+":"+fields.get("reason"));
        return Map.of("id",horse,"is_training_locked",false,"suggest_status_update",List.of("Injured","Quarantine").contains(old.get("current_status")));
    }

    /** Flow 2 must call inside its write transaction BEFORE creating plans/events. Holds horse row lock. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void assertHorseNotLocked(String horse) {
        var row=horse(horse,true);
        if(Boolean.TRUE.equals(row.get("is_training_locked"))) {
            Map<String,Object> details=new LinkedHashMap<>(); details.put("lock_level",row.get("lock_level")); details.put("lock_reason",row.get("lock_reason"));
            throw new VetException(409,"TRAINING_LOCKED","Ngựa đang bị khóa huấn luyện",details);
        }
    }
    private Map<String,Object> horse(String id,boolean lock) {
        access.requireHorse(id,lock); return data.db.query("SELECT * FROM horses WHERE id=?",VetRows.MAPPER,id).getFirst();
    }
    private void notifyTrainers(String horse,String message) {
        var recipients=data.db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                +"WHERE u.status='APPROVED' AND u.deleted_at IS NULL AND r.role_name='HEAD_TRAINER'",Long.class);
        for(long recipient:recipients) notifications.schedule(recipient,"Horses",horse,message,time.now());
    }
}
