package com.example.springbootbackend.veterinarian.care;

import com.example.springbootbackend.veterinarian.support.*;
import com.example.springbootbackend.veterinarian.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class CareScheduleService {
    private static final String SOURCE="Periodic_Care_Schedules";
    private static final Map<String,String> LABELS=Map.of("HoofCheck","kiểm tra móng","Deworming","tẩy giun","Vaccination","tiêm phòng","MedicalCheckup","khám định kỳ");
    private final VetData data;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final NotificationService notifications;
    private final LocalTime reminderTime;
    private final String doctorLock;
    public CareScheduleService(VetData data,VetAccess access,VetAudit audit,VetTime time,NotificationService notifications,
            @Value("${app.vet.reminder-time:08:00}") String reminderTime) {
        this.data=data; this.access=access; this.audit=audit; this.time=time; this.notifications=notifications;
        this.reminderTime=LocalTime.parse(reminderTime);
        boolean postgres=Boolean.TRUE.equals(data.db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection -> connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        // Serialize doctor bookings without blocking foreign-key inserts into audit/medical tables.
        this.doctorLock=postgres?" FOR NO KEY UPDATE":" FOR UPDATE";
    }

    @Transactional
    public Map<String,Object> create(String email,String ip,JsonNode body) {
        long actor=access.requireUser(email,true);
        var fields=VetInput.parse(body,"horse_id:uuid","care_type:30","frequency_days:int","last_done_date:date","next_due_date:date",
                "assigned_doctor_id:long","notes:0","book_event:bool","start_time:time","end_time:time");
        VetInput.required(fields,"horse_id"); VetInput.choice(fields,"care_type","HoofCheck","Deworming","Vaccination","MedicalCheckup");
        if(fields.get("frequency_days")!=null && (int)fields.get("frequency_days")<1) throw VetException.invalid("frequency_days","Phải >= 1");
        if(fields.get("last_done_date")!=null && ((LocalDate)fields.get("last_done_date")).isAfter(time.today())) throw VetException.invalid("last_done_date","Không được ở tương lai");
        if(fields.get("next_due_date")==null && fields.get("last_done_date")!=null && fields.get("frequency_days")!=null)
            fields.put("next_due_date",nextDate((LocalDate)fields.get("last_done_date"),(int)fields.get("frequency_days")));
        VetInput.required(fields,"next_due_date"); future((LocalDate)fields.get("next_due_date"));
        fields.putIfAbsent("assigned_doctor_id",actor); VetInput.required(fields,"assigned_doctor_id");
        long doctor=((Number)fields.get("assigned_doctor_id")).longValue(); lockDoctor(doctor,true);
        String horse=fields.get("horse_id").toString(); access.requireHorse(horse,true);
        fields.putIfAbsent("book_event",true); VetInput.required(fields,"book_event");
        boolean book=(boolean)fields.remove("book_event"); LocalTime start=(LocalTime)fields.remove("start_time"), end=(LocalTime)fields.remove("end_time");
        String id=UUID.randomUUID().toString(); fields.put("id",id); fields.put("created_at",time.utcNow());
        if(book) {
            times(start,end);
            requireNoConflicts(horse,doctor,(LocalDate)fields.get("next_due_date"),start,end,null);
            var event=writeEvent(fields,(LocalDate)fields.get("next_due_date"),start,end,actor,null);
            fields.put("calendar_event_id",event.get("id"));
        }
        data.insert("periodic_care_schedules",fields);
        if(book) reminder(fields,event(fields.get("calendar_event_id")));
        audit.record(actor,ip,"CREATE_PERIODIC_CARE_SCHEDULE:"+id); return response(id);
    }

    @Transactional
    public Map<String,Object> book(String email,String ip,String id,JsonNode body) {
        long actor=access.requireUser(email,true); var schedule=lockedSchedule(id);
        if(schedule.get("calendar_event_id")!=null) throw new VetException(409,"EVENT_ALREADY_BOOKED","Quy tắc đã có sự kiện");
        var fields=VetInput.parse(body,"event_date:date","start_time:time","end_time:time");
        fields.putIfAbsent("event_date",schedule.get("next_due_date")); VetInput.required(fields,"event_date");
        LocalDate date=(LocalDate)fields.get("event_date"); LocalTime start=(LocalTime)fields.get("start_time"), end=(LocalTime)fields.get("end_time");
        future(date); times(start,end);
        requireNoConflicts(horse(schedule),doctor(schedule),date,start,end,null);
        var event=writeEvent(schedule,date,start,end,actor,null);
        data.update("periodic_care_schedules",id,Map.of("calendar_event_id",event.get("id"),"next_due_date",date));
        reminder(schedule,event); audit.record(actor,ip,"BOOK_CARE_EVENT:"+id); return response(id);
    }

    @Transactional
    public Map<String,Object> move(String email,String ip,String id,JsonNode body) {
        long actor=access.requireUser(email,true); var schedule=lockedSchedule(id); var current=scheduledEvent(schedule);
        var fields=VetInput.parse(body,"event_date:date","start_time:time","end_time:time");
        var merged=new LinkedHashMap<>(current); merged.putAll(fields);
        VetInput.required(merged,"event_date"); LocalDate date=(LocalDate)merged.get("event_date");
        LocalTime start=(LocalTime)merged.get("start_time"),end=(LocalTime)merged.get("end_time"); future(date); times(start,end);
        String currentId=current.get("id").toString(); requireNoConflicts(horse(schedule),doctor(schedule),date,start,end,currentId);
        removeFutureReminders(currentId);
        var moved=writeEvent(schedule,date,start,end,actor,current);
        data.update("periodic_care_schedules",id,Map.of("calendar_event_id",moved.get("id"),"next_due_date",date));
        reminder(schedule,moved); audit.record(actor,ip,"MOVE_CARE_EVENT:"+id); return response(id);
    }

    @Transactional
    public Map<String,Object> cancel(String email,String ip,String id) {
        long actor=access.requireUser(email,true); var schedule=lockedSchedule(id); var current=scheduledEvent(schedule);
        String eventId=current.get("id").toString();
        data.update("calendar_events",eventId,Map.of("status","Cancelled")); removeFutureReminders(eventId);
        data.db.update("UPDATE periodic_care_schedules SET calendar_event_id=NULL WHERE id=?",id);
        audit.record(actor,ip,"CANCEL_CARE_EVENT:"+id); return response(id);
    }

    @Transactional
    public Map<String,Object> complete(String email,String ip,String id,JsonNode body) {
        long actor=access.requireUser(email,true); var schedule=lockedSchedule(id);
        var fields=VetInput.parse(body,"done_date:date","next_start_time:time","next_end_time:time");
        fields.putIfAbsent("done_date",time.today()); VetInput.required(fields,"done_date");
        LocalDate done=(LocalDate)fields.get("done_date");
        if(done.isAfter(time.today())) throw VetException.invalid("done_date","Không được ở tương lai");
        var current=event(schedule.get("calendar_event_id"));
        // The endpoint addresses a moving pointer; prevent an immediate retry from completing the next occurrence.
        if((current!=null && "Completed".equals(current.get("status")))
                || (schedule.get("last_done_date")!=null && !done.isAfter((LocalDate)schedule.get("last_done_date"))))
            throw new VetException(409,"EVENT_ALREADY_COMPLETED","Lần chăm sóc này đã được hoàn thành");
        String completedId=null;
        if(current!=null && "Scheduled".equals(current.get("status"))) {
            completedId=current.get("id").toString(); data.update("calendar_events",completedId,Map.of("status","Completed")); removeFutureReminders(completedId);
        }
        Map<String,Object> patch=new LinkedHashMap<>(); patch.put("last_done_date",done);
        Map<String,Object> next=null; List<Map<String,Object>> warnings=new ArrayList<>();
        if(schedule.get("frequency_days")!=null) {
            LocalDate nextDate=nextDate(done,((Number)schedule.get("frequency_days")).intValue()); patch.put("next_due_date",nextDate);
            LocalTime start=(LocalTime)(fields.containsKey("next_start_time")?fields.get("next_start_time"):current==null?null:current.get("start_time"));
            LocalTime end=(LocalTime)(fields.containsKey("next_end_time")?fields.get("next_end_time"):current==null?null:current.get("end_time"));
            var conflicts=(start!=null && end!=null && end.isAfter(start))?checkCareConflicts(horse(schedule),doctor(schedule),nextDate,start,end,null):List.<Map<String,Object>>of();
            var occupied=sourceEvent(id,nextDate);
            boolean usable=occupied==null || "Cancelled".equals(occupied.get("status"));
            if(start==null || end==null || !end.isAfter(start) || nextDate.isBefore(time.today()) || !conflicts.isEmpty() || !usable) {
                patch.put("calendar_event_id",null);
                warnings.add(Map.of("code","NEXT_EVENT_NOT_BOOKED","reason","Thiếu giờ, ngày đã qua hoặc có lịch xung đột","conflicts",conflicts));
            } else {
                next=writeEvent(schedule,nextDate,start,end,actor,null); patch.put("calendar_event_id",next.get("id")); reminder(schedule,next);
            }
        } else if(current==null || "Cancelled".equals(current.get("status"))) {
            // Preserve a completed occurrence even for an unbooked one-off rule.
            var existing=sourceEvent(id,done);
            Map<String,Object> completed=new LinkedHashMap<>();
            completed.put("horse_id",horse(schedule)); completed.put("event_type",schedule.get("care_type"));
            completed.put("event_date",done); completed.put("status","Completed"); completed.put("source_table",SOURCE);
            completed.put("source_id",id); completed.put("created_by",actor); completed.put("created_at",time.utcNow());
            if(existing==null) completedId=data.insert("calendar_events",completed);
            else { completedId=existing.get("id").toString(); data.update("calendar_events",completedId,Map.of("status","Completed")); }
            patch.put("calendar_event_id",completedId);
        }
        data.update("periodic_care_schedules",id,patch); audit.record(actor,ip,"COMPLETE_CARE_SCHEDULE:"+id);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("schedule",response(id)); result.put("completed_event_id",completedId);
        result.put("next_event",next); result.put("warnings",warnings); return result;
    }

    public Map<String,Object> list(String email,String horse,String care,Integer days,ApiPage page) {
        access.requireUser(email,true); validateCare(care);
        if(horse!=null) access.requireHorse(horse,false);
        if(days!=null && days<0) throw VetException.invalid("due_within_days","Phải >= 0");
        String from=" FROM periodic_care_schedules p JOIN horses h ON h.id=p.horse_id LEFT JOIN calendar_events ce ON ce.id=p.calendar_event_id";
        String where=" WHERE h.deleted_at IS NULL"; var args=new ArrayList<Object>();
        if(horse!=null) { where+=" AND p.horse_id=?"; args.add(horse); }
        if(care!=null) { where+=" AND p.care_type=?"; args.add(care); }
        if(days!=null) { where+=" AND p.next_due_date<=?"; args.add(nextDate(time.today(),days)); }
        var result=data.page("SELECT p.*,ce.status AS event_status"+from,"SELECT count(*)"+from,where," ORDER BY p.next_due_date,p.id",args,page);
        for(Object value:(List<?>)result.get("data")) {
            @SuppressWarnings("unchecked") var row=(Map<String,Object>)value;
            row.put("is_overdue",((LocalDate)row.get("next_due_date")).isBefore(time.today()) && !"Completed".equals(row.remove("event_status")));
        }
        return result;
    }

    public Map<String,Object> calendar(String email,LocalDate from,LocalDate to,String scope,String horse,String care,boolean context,ApiPage page) {
        long actor=access.requireUser(email,true); validateCare(care);
        if(!List.of("mine","all").contains(scope)) throw VetException.invalid("scope","Chỉ nhận mine hoặc all");
        if(from==null || to==null || from.isAfter(to)) throw VetException.invalid("from","Cần from và to hợp lệ");
        if(horse!=null) access.requireHorse(horse,false);
        String join=" FROM calendar_events ce JOIN periodic_care_schedules p ON ce.source_id=p.id AND ce.source_table='"+SOURCE+"' "
                +"JOIN horses h ON h.id=ce.horse_id LEFT JOIN users u ON u.user_id=p.assigned_doctor_id";
        String where=" WHERE h.deleted_at IS NULL AND ce.event_date>=? AND ce.event_date<=?";
        var args=new ArrayList<Object>(List.of(from,to));
        if(scope.equals("mine")) { where+=" AND p.assigned_doctor_id=?"; args.add(actor); }
        if(horse!=null) { where+=" AND ce.horse_id=?"; args.add(horse); }
        if(care!=null) { where+=" AND p.care_type=?"; args.add(care); }
        String select="SELECT ce.id AS event_id,ce.event_date,ce.start_time,ce.end_time,ce.status,p.care_type,h.id AS horse_id,h.horse_name,p.id AS schedule_id,p.assigned_doctor_id,u.full_name AS doctor_name,p.notes";
        var result=new LinkedHashMap<>(data.page(select+join,"SELECT count(*)"+join,where," ORDER BY ce.event_date,ce.start_time,ce.id",args,page));
        List<String> horses=new ArrayList<>();
        for(Object value:(List<?>)result.get("data")) {
            @SuppressWarnings("unchecked") var row=(Map<String,Object>)value;
            horses.add(row.get("horse_id").toString());
            row.put("horse",Map.of("id",row.remove("horse_id"),"horse_name",row.remove("horse_name")));
            row.put("assigned_doctor",VetRows.person(row.remove("assigned_doctor_id"),row.remove("doctor_name")));
            row.put("context",false);
        }
        if(context) {
            // Context is limited to horses on this page, and independently paged to bound response size.
            var unique=horses.stream().distinct().toList();
            List<Map<String,Object>> extra=List.of(); long total=0;
            if(!unique.isEmpty()) {
                String contextWhere=" WHERE h.deleted_at IS NULL AND ce.event_date>=? AND ce.event_date<=? AND ce.source_table<>? AND ce.horse_id IN ("
                        +String.join(",",Collections.nCopies(unique.size(),"?"))+")";
                var params=new ArrayList<Object>(List.of(from,to,SOURCE)); params.addAll(unique);
                total=data.db.queryForObject("SELECT count(*) FROM calendar_events ce JOIN horses h ON h.id=ce.horse_id"+contextWhere,Long.class,params.toArray());
                params.add(page.limit());
                extra=data.db.query("SELECT ce.*,h.horse_name FROM calendar_events ce JOIN horses h ON h.id=ce.horse_id"+contextWhere+" ORDER BY ce.event_date,ce.start_time,ce.id LIMIT ?",VetRows.MAPPER,params.toArray());
                extra.forEach(row->row.put("context",true));
            }
            result.put("context_events",extra); result.put("context_total",total);
        }
        return result;
    }

    public List<Map<String,Object>> checkCareConflicts(String horse,long doctor,LocalDate date,LocalTime start,LocalTime end,String exclude) {
        String sql="SELECT DISTINCT ce.id AS event_id,ce.event_type,ce.event_date,ce.start_time,ce.end_time,h.horse_name "
                +"FROM calendar_events ce JOIN horses h ON h.id=ce.horse_id "
                +"LEFT JOIN periodic_care_schedules p ON ce.source_id=p.id AND ce.source_table='"+SOURCE+"' "
                +"WHERE h.deleted_at IS NULL AND ce.event_date=? AND ce.status<>'Cancelled' AND ce.event_type NOT IN ('CareTask','Rest') "
                +"AND (ce.horse_id=? OR p.assigned_doctor_id=?) AND (ce.start_time IS NULL OR ce.end_time IS NULL OR (ce.start_time<? AND ce.end_time>?))";
        var args=new ArrayList<Object>(List.of(date,horse,doctor,end,start));
        if(exclude!=null) { sql+=" AND ce.id<>?"; args.add(exclude); }
        return data.db.query(sql+" ORDER BY ce.event_date,ce.start_time,ce.id",VetRows.MAPPER,args.toArray());
    }
    private void requireNoConflicts(String horse,long doctor,LocalDate date,LocalTime start,LocalTime end,String exclude) {
        var conflicts=checkCareConflicts(horse,doctor,date,start,end,exclude);
        if(!conflicts.isEmpty()) throw new VetException(409,"SCHEDULE_CONFLICT","Trùng lịch ngựa hoặc bác sĩ",Map.of("conflicts",conflicts));
    }
    private void lockDoctor(long doctor,boolean active) {
        var rows=data.db.queryForList("SELECT user_id,status,role_id,deleted_at FROM users WHERE user_id=?"+doctorLock,doctor);
        if(rows.isEmpty()) throw VetException.invalid("assigned_doctor_id","Không tìm thấy bác sĩ");
        var user=rows.getFirst();
        String role=data.db.queryForObject("SELECT role_name FROM roles WHERE role_id=?",String.class,user.get("role_id"));
        if(active && (!"VETERINARIAN".equals(role) || !"APPROVED".equals(user.get("status")) || user.get("deleted_at")!=null))
            throw VetException.invalid("assigned_doctor_id","Phải là bác sĩ đang hoạt động");
    }
    private Map<String,Object> lockedSchedule(String id) {
        var rows=data.db.query("SELECT * FROM periodic_care_schedules WHERE id=?",VetRows.MAPPER,id);
        if(rows.isEmpty()) throw new VetException(404,"NOT_FOUND","Không tìm thấy lịch chăm sóc");
        var row=rows.getFirst();
        if(row.get("assigned_doctor_id")!=null) lockDoctor(doctor(row),false);
        return data.row("periodic_care_schedules",id,true);
    }
    private Map<String,Object> scheduledEvent(Map<String,Object> schedule) {
        var event=event(schedule.get("calendar_event_id"));
        if(event==null || !"Scheduled".equals(event.get("status"))) throw new VetException(409,"EVENT_NOT_SCHEDULED","Sự kiện không ở trạng thái Scheduled");
        return event;
    }
    private Map<String,Object> event(Object id) {
        if(id==null) return null;
        var rows=data.db.query("SELECT * FROM calendar_events WHERE id=?",VetRows.MAPPER,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Map<String,Object> sourceEvent(String schedule,LocalDate date) {
        var rows=data.db.query("SELECT * FROM calendar_events WHERE source_table=? AND source_id=? AND event_date=?",VetRows.MAPPER,SOURCE,schedule,date);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Map<String,Object> writeEvent(Map<String,Object> schedule,LocalDate date,LocalTime start,LocalTime end,long actor,Map<String,Object> current) {
        String id=schedule.get("id").toString(); var reuse=sourceEvent(id,date);
        String currentId=current==null?null:current.get("id").toString();
        if(reuse!=null && !reuse.get("id").equals(currentId) && !"Cancelled".equals(reuse.get("status")))
            throw new VetException(409,"SCHEDULE_CONFLICT","Quy tắc đã có sự kiện trong ngày này",Map.of("conflicts",List.of(reuse)));
        Map<String,Object> fields=new LinkedHashMap<>(); fields.put("horse_id",horse(schedule)); fields.put("event_type",schedule.get("care_type"));
        String name=data.db.queryForObject("SELECT horse_name FROM horses WHERE id=?",String.class,horse(schedule));
        fields.put("title",LABELS.get(schedule.get("care_type"))+" - "+name); fields.put("event_date",date); fields.put("start_time",start); fields.put("end_time",end);
        fields.put("status","Scheduled"); fields.put("source_table",SOURCE); fields.put("source_id",id);
        String eventId;
        if(reuse!=null) {
            eventId=reuse.get("id").toString();
            if(currentId!=null && !currentId.equals(eventId)) data.update("calendar_events",currentId,Map.of("status","Cancelled"));
            data.update("calendar_events",eventId,fields);
        } else if(currentId!=null) { eventId=currentId; data.update("calendar_events",eventId,fields); }
        else { fields.put("created_by",actor); fields.put("created_at",time.utcNow()); eventId=data.insert("calendar_events",fields); }
        return event(eventId);
    }
    private void reminder(Map<String,Object> schedule,Map<String,Object> event) {
        if(schedule.get("assigned_doctor_id")==null) return;
        Instant now=time.now(); Instant due=((LocalDate)event.get("event_date")).minusDays(1).atTime(reminderTime).atZone(VetTime.BUSINESS_ZONE).toInstant();
        if(due.isBefore(now)) due=now;
        removeFutureReminders(event.get("id").toString());
        String name=data.db.queryForObject("SELECT horse_name FROM horses WHERE id=?",String.class,horse(schedule));
        notifications.schedule(doctor(schedule),"Calendar_Events",event.get("id").toString(),"Ngày mai ("+event.get("event_date")+") có lịch "+LABELS.get(schedule.get("care_type"))+" cho ngựa "+name+" lúc "+event.get("start_time"),due);
    }
    private void removeFutureReminders(String event) {
        data.db.update("DELETE FROM notifications WHERE related_table='Calendar_Events' AND related_id=? AND scheduled_at>?",event,time.utcNow());
    }
    private Map<String,Object> response(String id) {
        var row=data.row("periodic_care_schedules",id,false); row.put("event",event(row.get("calendar_event_id"))); return row;
    }
    private String horse(Map<String,Object> schedule) { return schedule.get("horse_id").toString(); }
    private long doctor(Map<String,Object> schedule) { return schedule.get("assigned_doctor_id")==null?-1:((Number)schedule.get("assigned_doctor_id")).longValue(); }
    private void future(LocalDate date) { if(date.isBefore(time.today())) throw VetException.invalid("event_date","Ngày phải >= hôm nay"); }
    private void times(LocalTime start,LocalTime end) { if(start==null || end==null || !end.isAfter(start)) throw VetException.invalid("end_time","Cần giờ bắt đầu và kết thúc; end_time > start_time"); }
    private void validateCare(String care) { if(care!=null && !LABELS.containsKey(care)) throw VetException.invalid("care_type","Loại chăm sóc không hợp lệ"); }
    private LocalDate nextDate(LocalDate date,int days) {
        try { return date.plusDays(days); } catch(DateTimeException ex) { throw VetException.invalid("frequency_days","Ngày vượt phạm vi cho phép"); }
    }
}
