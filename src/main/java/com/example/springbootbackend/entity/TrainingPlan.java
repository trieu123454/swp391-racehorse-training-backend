package com.example.springbootbackend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "training_plans")
public class TrainingPlan {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "horse_id", length = 36)
    private String horseId;

    @Column(name = "stage_name", nullable = false, length = 100)
    private String stageName;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "objective", columnDefinition = "TEXT")
    private String objective;

    @Column(name = "target_distance_meters")
    private Integer targetDistanceMeters;

    @Column(name = "target_intensity", length = 20)
    private String targetIntensity;

    @Column(name = "target_workload_minutes")
    private Integer targetWorkloadMinutes;

    @Column(name = "target_track_surface", length = 50)
    private String targetTrackSurface;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "update_deadline_hours")
    private Integer updateDeadlineHours = 24;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHorseId() {
        return horseId;
    }

    public void setHorseId(String horseId) {
        this.horseId = horseId;
    }

    public String getStageName() {
        return stageName;
    }

    public void setStageName(String stageName) {
        this.stageName = stageName;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getObjective() {
        return objective;
    }

    public void setObjective(String objective) {
        this.objective = objective;
    }

    public Integer getTargetDistanceMeters() {
        return targetDistanceMeters;
    }

    public void setTargetDistanceMeters(Integer targetDistanceMeters) {
        this.targetDistanceMeters = targetDistanceMeters;
    }

    public String getTargetIntensity() {
        return targetIntensity;
    }

    public void setTargetIntensity(String targetIntensity) {
        this.targetIntensity = targetIntensity;
    }

    public Integer getTargetWorkloadMinutes() {
        return targetWorkloadMinutes;
    }

    public void setTargetWorkloadMinutes(Integer targetWorkloadMinutes) {
        this.targetWorkloadMinutes = targetWorkloadMinutes;
    }

    public String getTargetTrackSurface() {
        return targetTrackSurface;
    }

    public void setTargetTrackSurface(String targetTrackSurface) {
        this.targetTrackSurface = targetTrackSurface;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Integer getUpdateDeadlineHours() {
        return updateDeadlineHours;
    }

    public void setUpdateDeadlineHours(Integer updateDeadlineHours) {
        this.updateDeadlineHours = updateDeadlineHours;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
