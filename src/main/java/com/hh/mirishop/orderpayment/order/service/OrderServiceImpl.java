package com.hh.mirishop.orderpayment.order.service;

import com.hh.mirishop.orderpayment.client.ProductServiceClient;
import com.hh.mirishop.orderpayment.client.UserFeignClient;
import com.hh.mirishop.orderpayment.client.dto.ProductResponse;
import com.hh.mirishop.orderpayment.common.exception.ErrorCode;
import com.hh.mirishop.orderpayment.common.exception.OrderException;
import com.hh.mirishop.orderpayment.order.domain.OrderStatus;
import com.hh.mirishop.orderpayment.order.dto.OrderAddressDto;
import com.hh.mirishop.orderpayment.order.dto.OrderCreate;
import com.hh.mirishop.orderpayment.order.enttiy.Order;
import com.hh.mirishop.orderpayment.order.enttiy.OrderItem;
import com.hh.mirishop.orderpayment.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final ProductServiceClient productServiceClient;
    private final UserFeignClient userFeignClient;
    private final OrderRepository orderRepository;

    /**
     * 주문
     */
    @Override
    @Transactional
    public Long createOrder(OrderCreate orderCreate, Long currentMemberNumber) {
        // 회원 조회
        userFeignClient.findMemberByNumber(currentMemberNumber);

        Long productId = orderCreate.getProductId();
        int count = orderCreate.getCount();

        // 상품 조회
        ProductResponse productResponse = productServiceClient.getProductById(productId);

        // 주문상품 생성
        OrderItem orderItem = OrderItem.builder()
                .productId(productResponse.getProductId())
                .orderPrice(productResponse.getPrice())
                .count(count)
                .build();

        // 재고 감소
        productServiceClient.decreaseStock(orderItem.getProductId(), orderItem.getCount());

        // 주문 생성
        Order order = Order.create(currentMemberNumber, orderItem);

        // 주문 저장
        orderRepository.save(order);
        return order.getOrderId();
    }

    /**
     * 주소 정보 추가
     */
    @Override
    @Transactional
    public void addAddressToOrder(Long orderId, OrderAddressDto address) {
        Order savedOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

        savedOrder.addAddress(address.getAddress());
    }

    /**
     * 주문 완료 처리
     */
    @Override
    @Transactional
    public void completeOrder(Long orderId, Long currentMemberNumber) {
        Order order = orderRepository.findByOrderIdAndMemberNumber(orderId, currentMemberNumber)
                .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

        // 주문 상태 검증
        if (order.getStatus() != OrderStatus.PAYMENT_WAITING) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS);
        }

        // 주문 상태 완료처리
        order.complete();

        // 변경된 주문 정보 저장
        orderRepository.save(order);
    }

    /**
     * 주문 취소
     */
    @Transactional
    public void cancelOrder(Long orderId, Long currentMemberNumber) {
        Order order = orderRepository.findByOrderIdAndMemberNumber(orderId, currentMemberNumber)
                .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

        validateOrder(order);
        order.cancel();

        order.getOrderItems().forEach(orderItem -> {
            productServiceClient.restoreStock(orderItem.getProductId(), orderItem.getCount());
        });

        orderRepository.save(order);
    }

    /**
     * 주문 조회
     */
    @Override
    @Transactional(readOnly = true)
    public Page<Order> findAllOrdersByMemberNumber(Long memberNumber, Pageable pageable) {
        return orderRepository.findAllByMemberNumber(memberNumber, pageable);
    }

    private void validateOrder(Order order) {
        if (order.getStatus() == OrderStatus.CANCEL) {
            throw new OrderException(ErrorCode.ORDER_STATUS_CANCELED);
        }
    }
}
