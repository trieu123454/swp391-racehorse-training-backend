package com.example.springbootbackend.headtrainer;

import com.example.springbootbackend.veterinarian.support.VetRows;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Select storage by schema metadata, never by swallowing SQL failures. Callers lock the session. */
@Repository
public class SimulationStorage {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public SimulationStorage(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    public boolean consolidated() {
        return db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema()"
                + " AND table_name='race_simulation_horses' AND column_name='metric_id'",Integer.class)>0;
    }
    public String resultsTable() { return consolidated() ? "race_simulation_horses" : "race_simulation_metrics"; }
    public String recordedPredicate(String alias) { return consolidated() ? alias+".metric_id IS NOT NULL" : "1=1"; }
    public List<Map<String,Object>> readings(String id) {
        if (!consolidated()) return db.query("SELECT * FROM training_simulation_readings WHERE simulation_id=? ORDER BY elapsed_seconds",VetRows.MAPPER,id);
        String value=db.queryForObject("SELECT readings_json FROM race_simulations WHERE id=?",String.class,id);
        if (value==null) return new ArrayList<>();
        try {
            List<Map<String,Object>> rows=json.readValue(value,new TypeReference<>() {});
            rows.sort(Comparator.comparingInt(row->((Number)row.get("elapsed_seconds")).intValue()));
            return rows;
        } catch (Exception failure) { throw new IllegalStateException("Invalid persisted simulation readings for "+id,failure); }
    }
    public void appendAll(String id, List<Map<String,Object>> added) {
        if (added.isEmpty()) return;
        if (!consolidated()) {
            for (var reading:added)
            db.update("INSERT INTO training_simulation_readings(simulation_id,elapsed_seconds,speed_kmh,heart_rate,bp_systolic,bp_diastolic) VALUES (?,?,?,?,?,?)",
                    id,reading.get("elapsed_seconds"),reading.get("speed_kmh"),reading.get("heart_rate"),reading.get("bp_systolic"),reading.get("bp_diastolic"));
            return;
        }
        var rows=readings(id); rows.addAll(added);
        try { db.update("UPDATE race_simulations SET readings_json=? WHERE id=?",json.writeValueAsString(rows),id); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Cannot serialize sensor readings",failure); }
    }
}
