package com.cems.api.repository;

import com.cems.api.entity.GeneratedReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GeneratedReportRepository extends JpaRepository<GeneratedReport, String> {

    List<GeneratedReport> findByReportTypeOrderByCreatedAtDesc(String reportType);
}
