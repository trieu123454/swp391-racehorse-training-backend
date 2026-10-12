package com.example.springbootbackend.veterinarian.medical;

import com.example.springbootbackend.veterinarian.support.*;
import com.example.springbootbackend.veterinarian.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class MedicalService {
    private final VetData data;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final NotificationService notifications;
    public MedicalService(VetData data,VetAccess access,VetAudit audit,VetTime time,NotificationService notifications) {
        this.data=data; this.access=access; this.audit=audit; this.time=time; this.notifications=notifications;
    }

    @Transactional
    public Map<String,Object> createRecord(String email,String ip,String horse,JsonNode body) {
        long actor=access.requireRole(email, "VETERINARIAN"); access.requireHorse(horse,true);
        var fields=VetInput.parse(body,"health_exam_id:uuid","diagnosis:0","treatment_plan:0");
        VetInput.required(fields,"diagnosis"); data.sameHorse("health_exams",fields.get("health_exam_id"),horse);
        fields.put("horse_id",horse); fields.put("doctor_id",actor); fields.put("created_at",time.utcNow());
        String id=data.insert("medical_records",fields); audit.record(actor,ip,"CREATE_MEDICAL_RECORD:"+id);
        return data.row("medical_records",id,false);
    }

    public Map<String,Object> record(String email,String id) {
        access.requireRole(email, "VETERINARIAN"); var row=data.row("medical_records",id,false);
        row.put("prescriptions",data.db.query("SELECT * FROM prescriptions WHERE medical_record_id=? ORDER BY created_at DESC,id DESC",VetRows.MAPPER,id));
        row.put("injury_markers",data.db.query("SELECT * FROM injury_markers WHERE medical_record_id=? ORDER BY marked_at DESC,id DESC",VetRows.MAPPER,id));
        return row;
    }

    @Transactional
    public Map<String,Object> updateRecord(String email,String ip,String id,JsonNode body) {
        long actor=access.requireRole(email, "VETERINARIAN"); var old=data.row("medical_records",id,true);
        var patch=VetInput.parse(body,"diagnosis:0","treatment_plan:0"); var merged=merge(old,patch);
        VetInput.required(merged,"diagnosis"); data.update("medical_records",id,patch);
        audit.record(actor,ip,"UPDATE_MEDICAL_RECORD:"+id); return data.row("medical_records",id,false);
    }

    public Map<String,Object> listRecords(String email,String horse,ApiPage page) {
        access.requireRole(email, "VETERINARIAN"); access.requireHorse(horse,false);
        return data.page("SELECT * FROM medical_records","SELECT count(*) FROM medical_records"," WHERE horse_id=?",
                " ORDER BY created_at DESC,id DESC",List.of(horse),page);
    }

    @Transactional
    public Map<String,Object> createPrescription(String email,String ip,String recordId,JsonNode body) {
        long actor=access.requireRole(email, "VETERINARIAN"); var record=data.row("medical_records",recordId,true);
        String horse=record.get("horse_id").toString();
        var fields=VetInput.parse(body,"drug_name:150","dosage:100","frequency:100","route:50","start_date:date","end_date:date","notes:0");
        VetInput.required(fields,"drug_name"); fields.putIfAbsent("start_date",time.today()); VetInput.dates(fields,"start_date","end_date");
        String where=" WHERE horse_id=? AND status='Active' AND lower(trim(drug_name))=lower(?) AND (end_date IS NULL OR end_date>=?)";
        var args=new ArrayList<Object>(List.of(horse,fields.get("drug_name"),fields.get("start_date")));
        if(fields.get("end_date")!=null) { where+=" AND start_date<=?"; args.add(fields.get("end_date")); }
        var duplicates=data.db.query("SELECT id,drug_name,start_date,end_date FROM prescriptions"+where,VetRows.MAPPER,args.toArray());
        fields.put("horse_id",horse); fields.put("doctor_id",actor); fields.put("medical_record_id",recordId);
        fields.put("status","Active"); fields.put("created_at",time.utcNow());
        String id=data.insert("prescriptions",fields); audit.record(actor,ip,"CREATE_PRESCRIPTION:"+id);
        var response=data.row("prescriptions",id,false);
        response.put("warnings",duplicates.isEmpty()?List.of():List.of(Map.of("code","DUPLICATE_DRUG","prescriptions",duplicates)));
        return response;
    }

    @Transactional
    public Map<String,Object> updatePrescription(String email,String ip,String id,JsonNode body) {
        long actor=access.requireRole(email, "VETERINARIAN"); var old=data.row("prescriptions",id,true);
        if(!"Active".equals(old.get("status"))) throw new VetException(409,"PRESCRIPTION_CLOSED","Đơn thuốc đã kết thúc");
        var patch=VetInput.parse(body,"dosage:100","frequency:100","route:50","end_date:date","notes:0","status:20");
        var merged=merge(old,patch); VetInput.choice(merged,"status","Active","Completed","Stopped");
        if(!"Active".equals(merged.get("status"))) {
            if(merged.get("end_date")==null || ((LocalDate)merged.get("end_date")).isAfter(time.today())) {
                patch.put("end_date",time.today()); merged.put("end_date",time.today());
            }
        }
        VetInput.dates(merged,"start_date","end_date"); patch.put("updated_at",time.utcNow());
        data.update("prescriptions",id,patch); audit.record(actor,ip,"UPDATE_PRESCRIPTION:"+id);
        return data.row("prescriptions",id,false);
    }

    public Map<String,Object> prescriptions(String email,String horse,String status,ApiPage page) {
        access.requireRole(email, "VETERINARIAN"); access.requireHorse(horse,false);
        String where=" WHERE horse_id=?"; var args=new ArrayList<Object>(List.of(horse));
        if(status!=null) {
            VetInput.choice(Map.of("status",status),"status","Active","Completed","Stopped"); where+=" AND status=?"; args.add(status);
        }
        return data.page("SELECT * FROM prescriptions","SELECT count(*) FROM prescriptions",where," ORDER BY created_at DESC,id DESC",args,page);
    }

    @Transactional
    public Map<String,Object> saveDiet(String email,String ip,String horse,String id,JsonNode body) {
        long actor=access.requireRole(email, "VETERINARIAN");
        Map<String,Object> old=id==null?new LinkedHashMap<>():data.row("diet_records",id,true);
        if(id!=null) horse=old.get("horse_id").toString();
        access.requireHorse(horse,true);
        var patch=VetInput.parse(body,"feed_type:100","quantity_kg:number","feeding_frequency:50","special_instructions:0","effective_date:date","end_date:date");
        if(id==null) patch.putIfAbsent("effective_date",time.today());
        var merged=merge(old,patch); VetInput.required(merged,"feed_type","quantity_kg");
        VetInput.number(merged,"quantity_kg","0.01","9999.99",2); VetInput.dates(merged,"effective_date","end_date");
        String where=" WHERE horse_id=? AND lower(trim(feed_type))=lower(?) AND (end_date IS NULL OR end_date>=?)";
        var args=new ArrayList<Object>(List.of(horse,merged.get("feed_type"),merged.get("effective_date")));
        if(merged.get("end_date")!=null) { where+=" AND (effective_date IS NULL OR effective_date<=?)"; args.add(merged.get("end_date")); }
        if(id!=null) { where+=" AND id<>?"; args.add(id); }
        if(data.db.queryForObject("SELECT count(*) FROM diet_records"+where,Long.class,args.toArray())>0)
            throw new VetException(409,"DIET_OVERLAP","Khẩu phần cùng loại bị trùng thời gian");
        if(id==null) {
            patch.put("horse_id",horse); patch.put("doctor_id",actor); patch.put("created_at",time.utcNow());
            id=data.insert("diet_records",patch); audit.record(actor,ip,"CREATE_DIET_RECORD:"+id);
        } else {
            patch.put("updated_at",time.utcNow()); data.update("diet_records",id,patch); audit.record(actor,ip,"UPDATE_DIET_RECORD:"+id);
        }
        return data.row("diet_records",id,false);
    }

    public Map<String,Object> diets(String email,String horse,LocalDate activeOn,boolean history,ApiPage page) {
        access.requireRole(email, "VETERINARIAN"); access.requireHorse(horse,false);
        String where=" WHERE horse_id=?"; var args=new ArrayList<Object>(List.of(horse));
        if(!history) {
            LocalDate date=activeOn==null?time.today():activeOn;
            where+=" AND effective_date<=? AND (end_date IS NULL OR end_date>=?)"; args.add(date); args.add(date);
        }
        return data.page("SELECT * FROM diet_records","SELECT count(*) FROM diet_records",where," ORDER BY effective_date DESC,id DESC",args,page);
    }

    @Transactional
    public Map<String,Object> createInjury(String email,String ip,String horse,JsonNode body) {
        long actor=access.requireRole(email, "VETERINARIAN"); access.requireHorse(horse,true);
        var fields=VetInput.parse(body,"medical_record_id:uuid","body_part:50","coordinate_x:number","coordinate_y:number","coordinate_z:number","severity:20","recovery_status:20","description:0");
        VetInput.required(fields,"body_part"); VetInput.choice(fields,"severity","Mild","Moderate","Severe");
        fields.putIfAbsent("recovery_status","Active"); VetInput.choice(fields,"recovery_status","Active","Recovering","Recovered");
        if((fields.get("coordinate_x")==null)!=(fields.get("coordinate_y")==null)) throw VetException.invalid("coordinate_y","Phải có cả coordinate_x và coordinate_y");
        for(String coordinate:List.of("coordinate_x","coordinate_y","coordinate_z")) VetInput.number(fields,coordinate,"-999.999","999.999",3);
        data.sameHorse("medical_records",fields.get("medical_record_id"),horse);
        fields.put("horse_id",horse); fields.put("marked_by",actor); fields.put("marked_at",time.utcNow());
        String id=data.insert("injury_markers",fields); audit.record(actor,ip,"CREATE_INJURY_MARKER:"+id);
        var result=data.row("injury_markers",id,false);
        if(!"Recovered".equals(fields.get("recovery_status"))) {
            var horseRow=data.db.query("SELECT current_status,horse_name,is_training_locked,lock_level FROM horses WHERE id=?",VetRows.MAPPER,horse).getFirst();
            Map<String,Object> updates=new LinkedHashMap<>();
            updates.put("current_status","Quarantine".equals(horseRow.get("current_status"))?"Quarantine":"Injured");
            updates.put("readiness_status","NotReady");
            updates.put("is_training_locked",true);
            updates.put("lock_level","Critical");
            updates.put("lock_reason","Active injury marker: "+fields.get("body_part"));
            data.update("horses",horse,updates);
            data.db.update("UPDATE training_schedules ts SET status='Blocked' FROM calendar_events ce "
                    +"WHERE ce.id=ts.calendar_event_id AND ts.horse_id=? AND ts.status='Scheduled' "
                    +"AND ce.status='Scheduled' AND ce.event_date>=? AND ts.session_type IN ('Training','TrialRun')",horse,time.today());
            data.db.update("UPDATE calendar_events ce SET status='Blocked' FROM training_schedules ts "
                    +"WHERE ts.calendar_event_id=ce.id AND ts.horse_id=? AND ts.status='Blocked' "
                    +"AND ce.status='Scheduled' AND ce.event_date>=? AND ts.session_type IN ('Training','TrialRun')",horse,time.today());
            if(!Boolean.TRUE.equals(horseRow.get("is_training_locked")) || !"Critical".equals(horseRow.get("lock_level")))
                notifyTrainingStaff(horse,horseRow.get("horse_name").toString(),fields.get("body_part").toString());
            result.put("training_locked",true);
        } else {
            result.put("training_locked",Boolean.TRUE.equals(data.db.queryForObject("SELECT is_training_locked FROM horses WHERE id=?",Boolean.class,horse)));
        }
        result.put("suggest_lock",false);
        return result;
    }

    private void notifyTrainingStaff(String horse,String horseName,String bodyPart) {
        String message="Horse "+horseName+" has an active injury marker at "+bodyPart+"; training has been locked.";
        var trainers=data.db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                +"WHERE u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL AND r.role_name='HEAD_TRAINER'",Long.class);
        for(long trainer:trainers) notifications.schedule(trainer,"Horses",horse,message,time.now());
        var grooms=data.db.queryForList("SELECT DISTINCT u.user_id FROM training_schedules ts "
                +"JOIN calendar_events ce ON ce.id=ts.calendar_event_id JOIN users u ON u.user_id=ts.assigned_groom_id "
                +"JOIN roles r ON r.role_id=u.role_id WHERE ts.horse_id=? AND ts.session_type IN ('Training','TrialRun') "
                +"AND ce.event_date>=? AND ce.status IN ('Scheduled','Blocked') AND r.role_name='GROOM' "
                +"AND u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL ORDER BY u.user_id",
                Long.class,horse,time.today());
        for(long groom:grooms) notifications.schedule(groom,"Horses",horse,message,time.now());
    }

    public Map<String,Object> injuries(String email,String horse,boolean latest,String bodyPart,ApiPage page) {
        access.requireRole(email, "VETERINARIAN"); access.requireHorse(horse,false);
        String source="(SELECT i.*,ROW_NUMBER() OVER(PARTITION BY lower(trim(body_part)) ORDER BY marked_at DESC,id DESC) AS position "
                +"FROM injury_markers i WHERE horse_id=?) ranked";
        String where=" WHERE 1=1"; var args=new ArrayList<Object>(List.of(horse));
        if(latest) where+=" AND position=1";
        if(bodyPart!=null) { where+=" AND body_part=?"; args.add(bodyPart.trim()); }
        var response=data.page("SELECT * FROM "+source,"SELECT count(*) FROM "+source,where," ORDER BY marked_at DESC,id DESC",args,page);
        ((List<?>)response.get("data")).forEach(row -> ((Map<?,?>)row).remove("position"));
        return response;
    }

    private Map<String,Object> merge(Map<String,Object> old,Map<String,Object> patch) {
        var merged=new LinkedHashMap<>(old); merged.putAll(patch); return merged;
    }
}
