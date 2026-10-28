package com.example.springbootbackend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "horse_identity_lock")
public class HorseIdentityLock {
    @Id
    @Column(name = "id")
    private Integer id;

    protected HorseIdentityLock() {}

    public Integer getId() { return id; }
}
