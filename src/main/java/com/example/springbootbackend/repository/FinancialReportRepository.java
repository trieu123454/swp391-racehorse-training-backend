package com.example.springbootbackend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springbootbackend.entity.FinancialReport;

public interface FinancialReportRepository extends JpaRepository<FinancialReport, String> {

    List<FinancialReport> findByHorseIdOrderByBillingPeriodDescCreatedAtDesc(String horseId);

    List<FinancialReport> findByBillingPeriodOrderByCreatedAtDesc(String billingPeriod);
}
