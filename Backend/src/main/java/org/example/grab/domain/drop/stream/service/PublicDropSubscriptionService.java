package org.example.grab.domain.drop.stream.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.global.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PublicDropSubscriptionService {

    private final DropRepository dropRepository;

    @Transactional(readOnly = true)
    public void validatePublicDrops(List<Long> dropIds) {
        if (dropRepository.countPublicDropsForSubscription(dropIds) != dropIds.size()) {
            // 혼합 요청에서도 어떤 ID가 비공개이거나 없는지 알려주지 않는다.
            throw new BusinessException(DropErrorCode.DROP_NOT_FOUND);
        }
    }
}
