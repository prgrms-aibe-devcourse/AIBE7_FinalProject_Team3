package org.example.grab.domain.seller.repository;

import org.example.grab.domain.seller.entity.DocumentType;
import org.example.grab.domain.seller.entity.SellerDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerDocumentRepository extends JpaRepository<SellerDocument, UUID> {

    // 신청 생성 시 4종 documentId가 모두 존재하는지 한 번에 확인한다.
    List<SellerDocument> findAllByIdIn(List<UUID> ids);

    // 운영자 서류 뷰어 조회용(GR-92)
    Optional<SellerDocument> findBySellerIdAndDocumentType(Long sellerId, DocumentType documentType);
}
