package com.example.springbootbackend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "horses")
public class Horse {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "horse_name", nullable = false, length = 100)
    private String horseName;

    @Column(name = "breed", length = 50)
    private String breed;

    @Column(name = "birth_year")
    private Integer birthYear;

    @Column(name = "height_cm", precision = 5, scale = 1)
    private BigDecimal heightCm;

    @Column(name = "pedigree_father", length = 100)
    private String pedigreeFather;

    @Column(name = "pedigree_mother", length = 100)
    private String pedigreeMother;

    @Column(name = "image_url", length = 512)
    private String imageUrl;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "stable_box_id", length = 36)
    private String stableBoxId;

    @Column(name = "current_status", nullable = false, length = 20)
    private String currentStatus = "Healthy";

    @Column(name = "current_weight_kg", precision = 6, scale = 2)
    private BigDecimal currentWeightKg;

    @Column(name = "readiness_status", length = 20)
    private String readinessStatus = "Unknown";

    @Column(name = "is_training_locked", nullable = false)
    private boolean trainingLocked = false;

    @Column(name = "lock_reason", length = 255)
    private String lockReason;

    @Column(name = "lock_level", length = 20)
    private String lockLevel;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHorseName() {
        return horseName;
    }

    public void setHorseName(String horseName) {
        this.horseName = horseName;
    }

    public String getBreed() {
        return breed;
    }

    public void setBreed(String breed) {
        this.breed = breed;
    }

    public Integer getBirthYear() {
        return birthYear;
    }

    public void setBirthYear(Integer birthYear) {
        this.birthYear = birthYear;
    }

    public BigDecimal getHeightCm() {
        return heightCm;
    }

    public void setHeightCm(BigDecimal heightCm) {
        this.heightCm = heightCm;
    }

    public String getPedigreeFather() {
        return pedigreeFather;
    }

    public void setPedigreeFather(String pedigreeFather) {
        this.pedigreeFather = pedigreeFather;
    }

    public String getPedigreeMother() {
        return pedigreeMother;
    }

    public void setPedigreeMother(String pedigreeMother) {
        this.pedigreeMother = pedigreeMother;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getStableBoxId() {
        return stableBoxId;
    }

    public void setStableBoxId(String stableBoxId) {
        this.stableBoxId = stableBoxId;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public void setCurrentStatus(String currentStatus) {
        this.currentStatus = currentStatus;
    }

    public BigDecimal getCurrentWeightKg() {
        return currentWeightKg;
    }

    public void setCurrentWeightKg(BigDecimal currentWeightKg) {
        this.currentWeightKg = currentWeightKg;
    }

    public String getReadinessStatus() {
        return readinessStatus;
    }

    public void setReadinessStatus(String readinessStatus) {
        this.readinessStatus = readinessStatus;
    }

    public boolean isTrainingLocked() {
        return trainingLocked;
    }

    public void setTrainingLocked(boolean trainingLocked) {
        this.trainingLocked = trainingLocked;
    }

    public String getLockReason() {
        return lockReason;
    }

    public void setLockReason(String lockReason) {
        this.lockReason = lockReason;
    }

    public String getLockLevel() {
        return lockLevel;
    }

    public void setLockLevel(String lockLevel) {
        this.lockLevel = lockLevel;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
