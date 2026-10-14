package org.example.grab.domain.seller.repository;

import org.example.grab.domain.seller.entity.SellerDocumentAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerDocumentAccessLogRepository extends JpaRepository<SellerDocumentAccessLog, Long> {
}
