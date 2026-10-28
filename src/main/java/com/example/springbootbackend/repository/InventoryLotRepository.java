package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.InventoryLot;

public interface InventoryLotRepository extends JpaRepository<InventoryLot, String> {

    List<InventoryLot> findByItemIdOrderByReceivedAtDescCreatedAtDesc(String itemId);
}
