package com.example.springbootbackend.veterinarian.support;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Table and column names here come exclusively from service constants, never request values. */
@Component
public class VetData {
    public final JdbcTemplate db;
    private final VetAccess access;
    public VetData(JdbcTemplate db,VetAccess access) { this.db=db; this.access=access; }
    public Map<String,Object> row(String table,String id,boolean lock) {
        var rows=db.query("SELECT * FROM "+table+" WHERE id=?",VetRows.MAPPER,id);
        if(rows.isEmpty()) throw new VetException(404,"NOT_FOUND","Không tìm thấy bản ghi");
        access.requireHorse(rows.getFirst().get("horse_id").toString(),lock);
        if(lock) rows=db.query("SELECT * FROM "+table+" WHERE id=? FOR UPDATE",VetRows.MAPPER,id);
        if(rows.isEmpty()) throw new VetException(404,"NOT_FOUND","Không tìm thấy bản ghi");
        return rows.getFirst();
    }
    public void sameHorse(String table,Object id,String horse) {
        if(id==null) return;
        Long count=db.queryForObject("SELECT count(*) FROM "+table+" WHERE id=? AND horse_id=?",Long.class,id,horse);
        if(count==null || count==0) throw VetException.invalid(table.equals("health_exams")?"health_exam_id":"medical_record_id","Bản ghi phải thuộc cùng ngựa");
    }
    public String insert(String table,Map<String,Object> data) {
        data.putIfAbsent("id",UUID.randomUUID().toString());
        db.update("INSERT INTO "+table+" ("+String.join(",",data.keySet())+") VALUES ("
                +String.join(",",Collections.nCopies(data.size(),"?"))+")",data.values().stream().map(VetRows::sqlValue).toArray());
        return data.get("id").toString();
    }
    public void update(String table,String id,Map<String,Object> data) {
        if(data.isEmpty()) return;
        var args=new ArrayList<Object>(data.values().stream().map(VetRows::sqlValue).toList()); args.add(id);
        db.update("UPDATE "+table+" SET "+String.join(",",data.keySet().stream().map(k->k+"=?").toList())+" WHERE id=?",args.toArray());
    }
    public Map<String,Object> page(String select,String count,String where,String order,List<Object> args,ApiPage page) {
        Long total=db.queryForObject(count+where,Long.class,args.toArray());
        var parameters=new ArrayList<>(args); parameters.add(page.limit()); parameters.add(page.offset());
        var rows=db.query(select+where+order+" LIMIT ? OFFSET ?",VetRows.MAPPER,parameters.toArray());
        return Map.of("data",rows,"total",total,"page",page.page(),"limit",page.limit());
    }
}
