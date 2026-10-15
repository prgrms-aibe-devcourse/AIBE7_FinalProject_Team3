package org.example.grab.domain.drop.stream.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.stream.model.StockStreamSubscription;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockStreamSubscriptionService {

    private final StockStreamQueryParser queryParser;
    private final PublicDropSubscriptionService publicDropService;
    private final StockStreamRegistry registry;

    public StockStreamSubscription subscribe(String remoteAddress, List<String> queryValues) {
        // 잘못된 쿼리와 존재하지 않는 상품 요청도 제한에 포함하되, 검증 전에 연결 슬롯을 점유하지 않는다.
        registry.recordAttempt(remoteAddress);
        List<Long> dropIds = queryParser.parse(queryValues);
        publicDropService.validatePublicDrops(dropIds);
        return registry.register(remoteAddress, dropIds);
    }
}
