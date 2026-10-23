package com.example.springbootbackend.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "training_plan_logs")
public class TrainingPlanLog {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "training_plan_id", nullable = false, length = 36)
    private String trainingPlanId;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "old_data_json", columnDefinition = "TEXT")
    private String oldDataJson;

    @Column(name = "new_data_json", columnDefinition = "TEXT")
    private String newDataJson;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTrainingPlanId() {
        return trainingPlanId;
    }

    public void setTrainingPlanId(String trainingPlanId) {
        this.trainingPlanId = trainingPlanId;
    }

    public Long getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(Long changedBy) {
        this.changedBy = changedBy;
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

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(LocalDateTime changedAt) {
        this.changedAt = changedAt;
    }
}
