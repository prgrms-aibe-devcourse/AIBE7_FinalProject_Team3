package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCreateResponse;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.StockReservation;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.OrderStockRepository;
import org.example.grab.domain.order.repository.OrderStockRepository.DropForOrder;
import org.example.grab.domain.order.repository.OrderStockRepository.OptionForOrder;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.ErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderCreationService {

    private static final long PAYMENT_TIMEOUT_MINUTES = 10;

    private final OrderRepository orderRepository;
    private final StockReservationRepository stockReservationRepository;
    private final OrderStockRepository orderStockRepository;
    private final OrderNumberGenerator orderNumberGenerator;
    private final RequestHasher requestHasher;
    private final Clock clock;

    @Transactional
    public OrderCreateResponse create(
            Long buyerId,
            String idempotencyKeyValue,
            OrderCreateRequest request
    ) {
        IdempotencyKey idempotencyKey = IdempotencyKey.from(idempotencyKeyValue);
        String requestHash = requestHasher.hash(request);

        if (!orderStockRepository.lockBuyer(buyerId)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        Order existingOrder = orderRepository.findWithItemsByBuyerIdAndIdempotencyKey(
                buyerId,
                idempotencyKey.value()
        ).orElse(null);
        if (existingOrder != null) {
            if (!existingOrder.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
            }
            return OrderCreateResponse.from(existingOrder);
        }

        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        DropForOrder drop = validateDrop(request.dropId(), now);
        Map<Long, Integer> requestedQuantities = aggregateQuantities(request.items());
        List<OptionForOrder> options = orderStockRepository.lockOptions(
                requestedQuantities.keySet().stream().sorted().toList()
        );
        validateOptions(drop.id(), requestedQuantities, options);

        long itemsAmount = calculateItemsAmount(requestedQuantities, options);
        OffsetDateTime paymentExpiresAt = now.plusMinutes(PAYMENT_TIMEOUT_MINUTES);
        Order order = createOrder(
                buyerId,
                idempotencyKey.value(),
                requestHash,
                request,
                drop,
                itemsAmount,
                paymentExpiresAt
        );

        for (OptionForOrder option : options) {
            int quantity = requestedQuantities.get(option.id());
            orderStockRepository.increaseReservedQuantity(option.id(), quantity);
            order.addItem(OrderItem.create(
                    drop.id(),
                    option.id(),
                    option.optionName(),
                    option.unitPrice(),
                    quantity
            ));
        }

        Order savedOrder = orderRepository.saveAndFlush(order);
        List<StockReservation> reservations = savedOrder.getItems().stream()
                .map(item -> StockReservation.hold(item, paymentExpiresAt))
                .toList();
        stockReservationRepository.saveAll(reservations);
        stockReservationRepository.flush();
        return OrderCreateResponse.from(savedOrder);
    }

    private DropForOrder validateDrop(Long dropId, OffsetDateTime now) {
        DropForOrder drop = orderStockRepository.lockDrop(dropId);
        if (drop == null || !"GRAB".equals(drop.status())) {
            throw new BusinessException(ErrorCode.DROP_NOT_ON_SALE);
        }
        if (now.isBefore(drop.saleStartsAt())) {
            throw new BusinessException(ErrorCode.SALE_NOT_STARTED);
        }
        if (!now.isBefore(drop.saleEndsAt())) {
            throw new BusinessException(ErrorCode.SALE_ENDED);
        }
        return drop;
    }

    private Map<Long, Integer> aggregateQuantities(List<OrderCreateRequest.OrderItemRequest> items) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        try {
            for (OrderCreateRequest.OrderItemRequest item : items) {
                quantities.merge(item.optionId(), item.quantity(), Math::addExact);
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return quantities;
    }

    private void validateOptions(
            Long dropId,
            Map<Long, Integer> requestedQuantities,
            List<OptionForOrder> options
    ) {
        if (options.size() != requestedQuantities.size()) {
            throw new BusinessException(ErrorCode.OPTION_NOT_FOUND);
        }
        for (OptionForOrder option : options) {
            if (!dropId.equals(option.dropId()) || !option.active()) {
                throw new BusinessException(ErrorCode.OPTION_NOT_FOUND);
            }
            if (option.availableQuantity() < requestedQuantities.get(option.id())) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
            }
        }
    }

    private long calculateItemsAmount(
            Map<Long, Integer> requestedQuantities,
            List<OptionForOrder> options
    ) {
        try {
            long amount = 0L;
            for (OptionForOrder option : options) {
                long subtotal = Math.multiplyExact(option.unitPrice(), requestedQuantities.get(option.id()));
                amount = Math.addExact(amount, subtotal);
            }
            return amount;
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private Order createOrder(
            Long buyerId,
            String idempotencyKey,
            String requestHash,
            OrderCreateRequest request,
            DropForOrder drop,
            long itemsAmount,
            OffsetDateTime paymentExpiresAt
    ) {
        OrderCreateRequest.ShippingAddressRequest address = request.shippingAddress();
        return Order.create(
                orderNumberGenerator.generate(),
                buyerId,
                drop.id(),
                idempotencyKey,
                requestHash,
                drop.name(),
                drop.sellerName(),
                itemsAmount,
                drop.shippingFee(),
                address.recipient(),
                address.phone(),
                address.postalCode(),
                address.address1(),
                address.address2(),
                address.deliveryMemo(),
                paymentExpiresAt
        );
    }
}
