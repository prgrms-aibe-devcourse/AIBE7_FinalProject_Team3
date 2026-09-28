package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderCreateTransactionService {

    private static final long PAYMENT_WAIT_MINUTES = 10;

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
        List<LockedOption> lockedOptions = inventoryRepository.lockOptions(drop.getId(), optionIds);
        if (lockedOptions.size() != optionIds.size()) {
            throw new BusinessException(OrderErrorCode.OPTION_NOT_FOUND);
        }
        Map<Long, LockedOption> optionsById = lockedOptions.stream()
                .collect(Collectors.toMap(LockedOption::getId, Function.identity()));

        long itemsAmount = 0;
        for (OrderCreateRequest.Item item : request.items()) {
            LockedOption option = optionsById.get(item.optionId());
            if (option == null || !option.isActive()) {
                throw new BusinessException(OrderErrorCode.OPTION_NOT_FOUND);
            }
            if (option.getAvailableQuantity() < item.quantity()) {
                throw new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK);
            }
            itemsAmount = Math.addExact(itemsAmount, Math.multiplyExact(option.getUnitPrice(), item.quantity()));
        }

        OffsetDateTime paymentExpiresAt = now.plusMinutes(PAYMENT_WAIT_MINUTES);
        Order order = orderRepository.saveAndFlush(Order.create(
                orderNumberGenerator.generate(),
                buyerId,
                drop.getId(),
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
            inventoryRepository.increaseReservedQuantity(option.getId(), requestItem.quantity());
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
        return order;
    }

    private void validateSale(DropSnapshot drop, OffsetDateTime now) {
        if (!"GRAB".equals(drop.getStatus())) {
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
