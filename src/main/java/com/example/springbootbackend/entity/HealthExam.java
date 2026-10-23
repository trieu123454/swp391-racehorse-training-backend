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
@Table(name = "health_exams")
public class HealthExam {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "horse_id", nullable = false, length = 36)
    private String horseId;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "exam_date")
    private LocalDateTime examDate;

    @Column(name = "temperature_c", precision = 4, scale = 1)
    private BigDecimal temperatureC;

    @Column(name = "heart_rate")
    private Integer heartRate;

    @Column(name = "respiratory_rate")
    private Integer respiratoryRate;

    @Column(name = "mucous_membrane_color", length = 50)
    private String mucousMembraneColor;

    @Column(name = "capillary_refill_sec", precision = 4, scale = 1)
    private BigDecimal capillaryRefillSec;

    @Column(name = "skin_turgor", length = 50)
    private String skinTurgor;

    @Column(name = "jugular_pulse", length = 50)
    private String jugularPulse;

    @Column(name = "digital_pulse", length = 50)
    private String digitalPulse;

    @Column(name = "gut_sounds", length = 50)
    private String gutSounds;

    @Column(name = "defecation_frequency", length = 50)
    private String defecationFrequency;

    @Column(name = "urination_frequency", length = 50)
    private String urinationFrequency;

    @Column(name = "body_condition_score", precision = 3, scale = 1)
    private BigDecimal bodyConditionScore;

    @Column(name = "hoof_temperature", length = 50)
    private String hoofTemperature;

    @Column(name = "gait_assessment", columnDefinition = "TEXT")
    private String gaitAssessment;

    @Column(name = "hematology_result", columnDefinition = "TEXT")
    private String hematologyResult;

    @Column(name = "biochemistry_result", columnDefinition = "TEXT")
    private String biochemistryResult;

    @Column(name = "fecal_test_result", columnDefinition = "TEXT")
    private String fecalTestResult;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

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

    public Long getDoctorId() {
        return doctorId;
    }

    public void setDoctorId(Long doctorId) {
        this.doctorId = doctorId;
    }

    public LocalDateTime getExamDate() {
        return examDate;
    }

    public void setExamDate(LocalDateTime examDate) {
        this.examDate = examDate;
    }

    public BigDecimal getTemperatureC() {
        return temperatureC;
    }

    public void setTemperatureC(BigDecimal temperatureC) {
        this.temperatureC = temperatureC;
    }

    public Integer getHeartRate() {
        return heartRate;
    }

    public void setHeartRate(Integer heartRate) {
        this.heartRate = heartRate;
    }

    public Integer getRespiratoryRate() {
        return respiratoryRate;
    }

    public void setRespiratoryRate(Integer respiratoryRate) {
        this.respiratoryRate = respiratoryRate;
    }

    public String getMucousMembraneColor() {
        return mucousMembraneColor;
    }

    public void setMucousMembraneColor(String mucousMembraneColor) {
        this.mucousMembraneColor = mucousMembraneColor;
    }

    public BigDecimal getCapillaryRefillSec() {
        return capillaryRefillSec;
    }

    public void setCapillaryRefillSec(BigDecimal capillaryRefillSec) {
        this.capillaryRefillSec = capillaryRefillSec;
    }

    public String getSkinTurgor() {
        return skinTurgor;
    }

    public void setSkinTurgor(String skinTurgor) {
        this.skinTurgor = skinTurgor;
    }

    public String getJugularPulse() {
        return jugularPulse;
    }

    public void setJugularPulse(String jugularPulse) {
        this.jugularPulse = jugularPulse;
    }

    public String getDigitalPulse() {
        return digitalPulse;
    }

    public void setDigitalPulse(String digitalPulse) {
        this.digitalPulse = digitalPulse;
    }

    public String getGutSounds() {
        return gutSounds;
    }

    public void setGutSounds(String gutSounds) {
        this.gutSounds = gutSounds;
    }

    public String getDefecationFrequency() {
        return defecationFrequency;
    }

    public void setDefecationFrequency(String defecationFrequency) {
        this.defecationFrequency = defecationFrequency;
    }

    public String getUrinationFrequency() {
        return urinationFrequency;
    }

    public void setUrinationFrequency(String urinationFrequency) {
        this.urinationFrequency = urinationFrequency;
    }

    public BigDecimal getBodyConditionScore() {
        return bodyConditionScore;
    }

    public void setBodyConditionScore(BigDecimal bodyConditionScore) {
        this.bodyConditionScore = bodyConditionScore;
    }

    public String getHoofTemperature() {
        return hoofTemperature;
    }

    public void setHoofTemperature(String hoofTemperature) {
        this.hoofTemperature = hoofTemperature;
    }

    public String getGaitAssessment() {
        return gaitAssessment;
    }

    public void setGaitAssessment(String gaitAssessment) {
        this.gaitAssessment = gaitAssessment;
    }

    public String getHematologyResult() {
        return hematologyResult;
    }

    public void setHematologyResult(String hematologyResult) {
        this.hematologyResult = hematologyResult;
    }

    public String getBiochemistryResult() {
        return biochemistryResult;
    }

    public void setBiochemistryResult(String biochemistryResult) {
        this.biochemistryResult = biochemistryResult;
    }

    public String getFecalTestResult() {
        return fecalTestResult;
    }

    public void setFecalTestResult(String fecalTestResult) {
        this.fecalTestResult = fecalTestResult;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
