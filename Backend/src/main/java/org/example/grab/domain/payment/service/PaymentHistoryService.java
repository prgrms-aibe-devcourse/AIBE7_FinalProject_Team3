package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.PayableOrder;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.dto.PaymentHistoryResponse;
import org.example.grab.domain.payment.dto.SellerPaymentHistoryResponse;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/*
    주문의 결제 시도와 결과를 시도 순서대로 조회한다(PAY-005, PAYMENT.md 1.3·1.4).
    조회 권한은 주문 도메인이 판정한다. 구매자는 남의 주문을 ORDER_NOT_FOUND로, 판매자는 남의 DROP 주문을 ORDER_ACCESS_DENIED로 받는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentHistoryService {

    private final OrderPaymentService orderPaymentService;
    private final PaymentRepository paymentRepository;

    public List<PaymentHistoryResponse> findBuyerHistory(long buyerId, UUID orderId) {
        return findHistory(orderPaymentService.findForBuyerPaymentHistory(buyerId, orderId),
                PaymentHistoryResponse::of);
    }

    public List<SellerPaymentHistoryResponse> findSellerHistory(long sellerId, UUID orderId) {
        return findHistory(orderPaymentService.findForSellerPaymentHistory(sellerId, orderId),
                SellerPaymentHistoryResponse::of);
    }

    private <T> List<T> findHistory(PayableOrder order, BiFunction<Payment, UUID, T> mapper) {
        return paymentRepository.findByOrderIdOrderByIdAsc(order.id()).stream()
                .map(payment -> mapper.apply(payment, order.orderId()))
                .toList();
    }
}
