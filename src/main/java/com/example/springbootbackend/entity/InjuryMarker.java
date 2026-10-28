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
@Table(name = "injury_markers")
public class InjuryMarker {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "horse_id", nullable = false, length = 36)
    private String horseId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "medical_record_id", length = 36)
    private String medicalRecordId;

    @Column(name = "body_part", length = 50)
    private String bodyPart;

    @Column(name = "coordinate_x", precision = 6, scale = 3)
    private BigDecimal coordinateX;

    @Column(name = "coordinate_y", precision = 6, scale = 3)
    private BigDecimal coordinateY;

    @Column(name = "coordinate_z", precision = 6, scale = 3)
    private BigDecimal coordinateZ;

    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "recovery_status", nullable = false, length = 20)
    private String recoveryStatus = "Active";

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "marked_by")
    private Long markedBy;

    @Column(name = "marked_at")
    private LocalDateTime markedAt;

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

    public String getMedicalRecordId() {
        return medicalRecordId;
    }

    public void setMedicalRecordId(String medicalRecordId) {
        this.medicalRecordId = medicalRecordId;
    }

    public String getBodyPart() {
        return bodyPart;
    }

    public void setBodyPart(String bodyPart) {
        this.bodyPart = bodyPart;
    }

    public BigDecimal getCoordinateX() {
        return coordinateX;
    }

    public void setCoordinateX(BigDecimal coordinateX) {
        this.coordinateX = coordinateX;
    }

    public BigDecimal getCoordinateY() {
        return coordinateY;
    }

    public void setCoordinateY(BigDecimal coordinateY) {
        this.coordinateY = coordinateY;
    }

    public BigDecimal getCoordinateZ() {
        return coordinateZ;
    }

    public void setCoordinateZ(BigDecimal coordinateZ) {
        this.coordinateZ = coordinateZ;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getRecoveryStatus() {
        return recoveryStatus;
    }

    public void setRecoveryStatus(String recoveryStatus) {
        this.recoveryStatus = recoveryStatus;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getMarkedBy() {
        return markedBy;
    }

    public void setMarkedBy(Long markedBy) {
        this.markedBy = markedBy;
    }

    public LocalDateTime getMarkedAt() {
        return markedAt;
    }

    public void setMarkedAt(LocalDateTime markedAt) {
        this.markedAt = markedAt;
    }
}
