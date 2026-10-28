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
@Table(name = "training_schedules")
public class TrainingSchedule {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "calendar_event_id", nullable = false, unique = true, length = 36)
    private String calendarEventId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "horse_id", nullable = false, length = 36)
    private String horseId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "training_plan_id", length = 36)
    private String trainingPlanId;

    @Column(name = "session_type", length = 20)
    private String sessionType = "Training";

    @Column(name = "track_surface", length = 50)
    private String trackSurface;

    @Column(name = "assigned_groom_id")
    private Long assignedGroomId;

    @Column(name = "status", length = 20)
    private String status = "Scheduled";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "target_distance_meters")
    private Integer targetDistanceMeters;

    @Column(name = "target_intensity", length = 20)
    private String targetIntensity;

    @Column(name = "snapshot_stage_name", length = 100)
    private String snapshotStageName;

    @Column(name = "snapshot_objective", columnDefinition = "TEXT")
    private String snapshotObjective;

    @Column(name = "snapshot_workload_minutes")
    private Integer snapshotWorkloadMinutes;

    @Column(name = "snapshot_track_surface", length = 50)
    private String snapshotTrackSurface;

    @Column(name = "snapshot_start_date")
    private LocalDate snapshotStartDate;

    @Column(name = "snapshot_end_date")
    private LocalDate snapshotEndDate;

    @Column(name = "trainer_review", columnDefinition = "TEXT")
    private String trainerReview;

    @Column(name = "review_updated_at")
    private LocalDateTime reviewUpdatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCalendarEventId() {
        return calendarEventId;
    }

    public void setCalendarEventId(String calendarEventId) {
        this.calendarEventId = calendarEventId;
    }

    public String getHorseId() {
        return horseId;
    }

    public void setHorseId(String horseId) {
        this.horseId = horseId;
    }

    public String getTrainingPlanId() {
        return trainingPlanId;
    }

    public void setTrainingPlanId(String trainingPlanId) {
        this.trainingPlanId = trainingPlanId;
    }

    public String getSessionType() {
        return sessionType;
    }

    public void setSessionType(String sessionType) {
        this.sessionType = sessionType;
    }

    public String getTrackSurface() {
        return trackSurface;
    }

    public void setTrackSurface(String trackSurface) {
        this.trackSurface = trackSurface;
    }

    public Long getAssignedGroomId() {
        return assignedGroomId;
    }

    public void setAssignedGroomId(Long assignedGroomId) {
        this.assignedGroomId = assignedGroomId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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

    public String getSnapshotStageName() {
        return snapshotStageName;
    }

    public void setSnapshotStageName(String snapshotStageName) {
        this.snapshotStageName = snapshotStageName;
    }

    public String getSnapshotObjective() {
        return snapshotObjective;
    }

    public void setSnapshotObjective(String snapshotObjective) {
        this.snapshotObjective = snapshotObjective;
    }

    public Integer getSnapshotWorkloadMinutes() {
        return snapshotWorkloadMinutes;
    }

    public void setSnapshotWorkloadMinutes(Integer snapshotWorkloadMinutes) {
        this.snapshotWorkloadMinutes = snapshotWorkloadMinutes;
    }

    public String getSnapshotTrackSurface() {
        return snapshotTrackSurface;
    }

    public void setSnapshotTrackSurface(String snapshotTrackSurface) {
        this.snapshotTrackSurface = snapshotTrackSurface;
    }

    public LocalDate getSnapshotStartDate() {
        return snapshotStartDate;
    }

    public void setSnapshotStartDate(LocalDate snapshotStartDate) {
        this.snapshotStartDate = snapshotStartDate;
    }

    public LocalDate getSnapshotEndDate() {
        return snapshotEndDate;
    }

    public void setSnapshotEndDate(LocalDate snapshotEndDate) {
        this.snapshotEndDate = snapshotEndDate;
    }

    public String getTrainerReview() {
        return trainerReview;
    }

    public void setTrainerReview(String trainerReview) {
        this.trainerReview = trainerReview;
    }

    public LocalDateTime getReviewUpdatedAt() {
        return reviewUpdatedAt;
    }

    public void setReviewUpdatedAt(LocalDateTime reviewUpdatedAt) {
        this.reviewUpdatedAt = reviewUpdatedAt;
    }
}
