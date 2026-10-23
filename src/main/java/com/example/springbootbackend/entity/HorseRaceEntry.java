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
@Table(name = "horse_race_entries")
public class HorseRaceEntry {

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
    @Column(name = "race_id", nullable = false, length = 36)
    private String raceId;

    @Column(name = "registered_by")
    private Long registeredBy;

    @Column(name = "result_position")
    private Integer resultPosition;

    @Column(name = "prize_amount", precision = 15, scale = 2)
    private BigDecimal prizeAmount;

    @Column(name = "status", length = 20)
    private String status = "Registered";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

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

    public String getRaceId() {
        return raceId;
    }

    public void setRaceId(String raceId) {
        this.raceId = raceId;
    }

    public Long getRegisteredBy() {
        return registeredBy;
    }

    public void setRegisteredBy(Long registeredBy) {
        this.registeredBy = registeredBy;
    }

    public Integer getResultPosition() {
        return resultPosition;
    }

    public void setResultPosition(Integer resultPosition) {
        this.resultPosition = resultPosition;
    }

    public BigDecimal getPrizeAmount() {
        return prizeAmount;
    }

    public void setPrizeAmount(BigDecimal prizeAmount) {
        this.prizeAmount = prizeAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
