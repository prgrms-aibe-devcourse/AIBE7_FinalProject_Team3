package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.stream.service.StockChangePublisher;
import org.example.grab.domain.drop.stream.model.StockChange;
import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.entity.StockReservation;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderInventoryRepository;
import org.example.grab.domain.order.repository.OrderInventoryRepository.DropSnapshot;
import org.example.grab.domain.order.repository.OrderInventoryRepository.LockedOption;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrderCreateTransactionService {

    private static final long PAYMENT_WAIT_MINUTES = 10;

    private final StockChangePublisher stockChangePublisher;
    private final OrderInventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockReservationRepository stockReservationRepository;
    private final OrderNumberGenerator orderNumberGenerator;

    @Transactional
    public Order create(
            Long buyerId,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash,
            OrderCreateRequest request
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DropSnapshot drop = inventoryRepository.findDrop(request.dropId())
                .orElseThrow(() -> new BusinessException(OrderErrorCode.DROP_NOT_ON_SALE));
        validateSale(drop, now);

        List<Long> optionIds = request.items().stream()
                .map(OrderCreateRequest.Item::optionId)
                .sorted()
                .toList();
        if (optionIds.size() != Set.copyOf(optionIds).size()) {
            throw new BusinessException(OrderErrorCode.OPTION_NOT_FOUND);
        }

        // 1. 락 없이 옵션 단가·옵션명·활성 여부만 먼저 읽어 주문/항목 INSERT에 사용한다.
        List<LockedOption> snapshots = inventoryRepository.findOptionSnapshots(drop.getId(), optionIds);
        Map<Long, LockedOption> optionsById = new LinkedHashMap<>();
        for (LockedOption snapshot : snapshots) {
            if (!snapshot.isActive()) {
                throw new BusinessException(OrderErrorCode.OPTION_NOT_FOUND);
            }
            optionsById.put(snapshot.getId(), snapshot);
        }
        if (optionsById.size() != optionIds.size()) {
            throw new BusinessException(OrderErrorCode.OPTION_NOT_FOUND);
        }

        Map<Long, Integer> quantityByOptionId = new LinkedHashMap<>();
        long itemsAmount = 0;
        for (OrderCreateRequest.Item requestItem : request.items()) {
            quantityByOptionId.put(requestItem.optionId(), requestItem.quantity());
            LockedOption option = optionsById.get(requestItem.optionId());
            itemsAmount = Math.addExact(itemsAmount, Math.multiplyExact(option.getUnitPrice(), requestItem.quantity()));
        }

        // 2. 경합 없는 주문·배송지·주문항목·예약 INSERT를 락 획득 전에 먼저 수행한다.
        OffsetDateTime paymentExpiresAt = now.plusMinutes(PAYMENT_WAIT_MINUTES);
        Order order = orderRepository.saveAndFlush(Order.create(
                orderNumberGenerator.generate(),
                buyerId,
                drop.getId(),
                drop.getSellerId(),
                idempotencyKey.value(),
                requestHash.value(),
                drop.getProductName(),
                drop.getSellerName(),
                itemsAmount,
                drop.getShippingFee(),
                toShippingAddress(request.shippingAddress()),
                paymentExpiresAt
        ));

        List<OrderItem> orderItems = new ArrayList<>();
        for (OrderCreateRequest.Item requestItem : request.items()) {
            LockedOption option = optionsById.get(requestItem.optionId());
            orderItems.add(OrderItem.create(
                    order,
                    option.getId(),
                    option.getOptionName(),
                    option.getUnitPrice(),
                    requestItem.quantity()
            ));
        }
        orderItemRepository.saveAllAndFlush(orderItems);
        stockReservationRepository.saveAll(orderItems.stream()
                .map(item -> StockReservation.hold(item, paymentExpiresAt))
                .toList());

        // 3. 커밋 직전 맨 마지막에 옵션 PK 오름차순으로 재고 행 잠금·차감을 실행해 락 보유 시간을 최소화한다.
        for (Long optionId : optionIds) {
            int quantity = quantityByOptionId.get(optionId);
            inventoryRepository
                    .tryReserveOption(drop.getId(), optionId, quantity)
                    .orElseGet(() -> diagnoseReserveFailure(drop.getId(), optionId, quantity));
        }

        stockChangePublisher.publish(order.getDropId(), StockChange.STOCK);
        return order;
    }

    // tryReserveOption이 빈 결과면 원인(없는 옵션·비활성·재고부족)을 가려 정확한 에러코드를 던진다.
    private LockedOption diagnoseReserveFailure(Long dropId, Long optionId, int quantity) {
        List<LockedOption> found = inventoryRepository.lockOptions(dropId, List.of(optionId));
        if (found.isEmpty()) {
            throw new BusinessException(OrderErrorCode.OPTION_NOT_FOUND);
        }
        LockedOption option = found.get(0);
        if (!option.isActive()) {
            throw new BusinessException(OrderErrorCode.OPTION_NOT_FOUND);
        }
        if (option.getAvailableQuantity() < quantity) {
            throw new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK);
        }
        // 진단 조회 사이에 다른 트랜잭션이 재고를 반환해 조건이 다시 맞아떨어진 경우 — 매우 드물지만 그대로 재시도한다.
        return inventoryRepository.tryReserveOption(dropId, optionId, quantity)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK));
    }

    /*
     * 구매 가능 여부는 저장 상태가 아니라 서버 시각과 판매 기간으로 판정한다(GR-18).
     * 저장 상태 전환은 최대 폴링 주기만큼 늦을 수 있으므로, WISH여도 판매 기간이면 허용한다.
     * DRAFT·CANCELED·ENDED는 시간과 무관하게 판매 대상이 아니다.
     */
    private void validateSale(DropSnapshot drop, OffsetDateTime now) {
        String status = drop.getStatus();
        if (!"WISH".equals(status) && !"GRAB".equals(status)) {
            throw new BusinessException(OrderErrorCode.DROP_NOT_ON_SALE);
        }
        Instant current = now.toInstant();
        if (current.isBefore(drop.getSaleStartsAt())) {
            throw new BusinessException(OrderErrorCode.SALE_NOT_STARTED);
        }
        if (!current.isBefore(drop.getSaleEndsAt())) {
            throw new BusinessException(OrderErrorCode.SALE_ENDED);
        }
    }

    private ShippingAddress toShippingAddress(OrderCreateRequest.ShippingAddress address) {
        return ShippingAddress.of(
                address.recipient(),
                address.phone(),
                address.postalCode(),
                address.address1(),
                address.address2(),
                address.deliveryMemo()
        );
    }
}
