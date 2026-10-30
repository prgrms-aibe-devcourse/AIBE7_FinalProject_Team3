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

    // 재신청 시 이전 신청에 붙어 있던 구 서류를 찾아 정리하기 위한 조회(GR-92 500 오류 원인)
    List<SellerDocument> findAllBySellerId(Long sellerId);

    /*
        운영자 서류 뷰어 조회용(GR-92). 반려 후 재신청 시 같은 sellerId·documentType으로
        서류가 다시 attach되어 여러 건이 남을 수 있어(SellerApplicationService.attachDocuments),
        가장 최근에 업로드된 한 건만 조회한다.
     */
    Optional<SellerDocument> findFirstBySellerIdAndDocumentTypeOrderByCreatedAtDesc(
            Long sellerId, DocumentType documentType);
}
