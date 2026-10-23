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
@Table(name = "horse_registration_requests")
public class HorseRegistrationRequest {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "request_type", nullable = false, length = 20)
    private String requestType;

    @Column(name = "horse_name", nullable = false, length = 100)
    private String horseName;

    @Column(name = "breed", length = 50)
    private String breed;

    @Column(name = "birth_year")
    private Integer birthYear;

    @Column(name = "height_cm", precision = 5, scale = 1)
    private BigDecimal heightCm;

    @Column(name = "current_weight_kg", precision = 6, scale = 2)
    private BigDecimal currentWeightKg;

    @Column(name = "pedigree_father", length = 100)
    private String pedigreeFather;

    @Column(name = "pedigree_mother", length = 100)
    private String pedigreeMother;

    @Column(name = "microchip_number", length = 100)
    private String microchipNumber;

    @Column(name = "passport_number", length = 100)
    private String passportNumber;

    @Column(name = "owner_note", columnDefinition = "TEXT")
    private String ownerNote;

    @Column(name = "horse_image_path", length = 512)
    private String horseImagePath;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "approved_horse_id", length = 36)
    private String approvedHorseId;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType;
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

    public BigDecimal getCurrentWeightKg() {
        return currentWeightKg;
    }

    public void setCurrentWeightKg(BigDecimal currentWeightKg) {
        this.currentWeightKg = currentWeightKg;
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

    public String getMicrochipNumber() {
        return microchipNumber;
    }

    public void setMicrochipNumber(String microchipNumber) {
        this.microchipNumber = microchipNumber;
    }

    public String getPassportNumber() {
        return passportNumber;
    }

    public void setPassportNumber(String passportNumber) {
        this.passportNumber = passportNumber;
    }

    public String getOwnerNote() {
        return ownerNote;
    }

    public void setOwnerNote(String ownerNote) {
        this.ownerNote = ownerNote;
    }

    public String getHorseImagePath() {
        return horseImagePath;
    }

    public void setHorseImagePath(String horseImagePath) {
        this.horseImagePath = horseImagePath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getApprovedHorseId() {
        return approvedHorseId;
    }

    public void setApprovedHorseId(String approvedHorseId) {
        this.approvedHorseId = approvedHorseId;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
