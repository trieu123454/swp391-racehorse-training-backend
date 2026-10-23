package com.example.springbootbackend.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "health_exam_logs")
public class HealthExamLog {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "health_exam_id", nullable = false, length = 36)
    private String healthExamId;

    @Column(name = "edited_by")
    private Long editedBy;

    @Column(name = "old_data_json", columnDefinition = "TEXT")
    private String oldDataJson;

    @Column(name = "new_data_json", columnDefinition = "TEXT")
    private String newDataJson;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHealthExamId() {
        return healthExamId;
    }

    public void setHealthExamId(String healthExamId) {
        this.healthExamId = healthExamId;
    }

    public Long getEditedBy() {
        return editedBy;
    }

    public void setEditedBy(Long editedBy) {
        this.editedBy = editedBy;
    }

    public String getOldDataJson() {
        return oldDataJson;
    }

    public void setOldDataJson(String oldDataJson) {
        this.oldDataJson = oldDataJson;
    }

    public String getNewDataJson() {
        return newDataJson;
    }

    public void setNewDataJson(String newDataJson) {
        this.newDataJson = newDataJson;
    }

    public LocalDateTime getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(LocalDateTime editedAt) {
        this.editedAt = editedAt;
    }
}
