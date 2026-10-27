package com.ledger.dataio;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, String> {

    List<ImportBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<ImportBatch> findByStatus(ImportBatch.Status status);
}
