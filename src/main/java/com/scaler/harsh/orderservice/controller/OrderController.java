package com.scaler.harsh.orderservice.controller;

import com.scaler.harsh.orderservice.dto.CreateOrderRequestDTO;
import com.scaler.harsh.orderservice.dto.DeliveryAddressDTO;
import com.scaler.harsh.orderservice.dto.OrderResponseDTO;
import com.scaler.harsh.orderservice.exceptions.ServiceException;
import com.scaler.harsh.orderservice.model.Order;
import com.scaler.harsh.orderservice.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService svc;

    public OrderController(OrderService svc) {
        this.svc = svc;
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderResponseDTO> placeOrder(@RequestBody CreateOrderRequestDTO request) {
        if (request.getUserId() == null || request.getUserId() <= 0) {
            throw new ServiceException("Valid userId is required", HttpStatus.BAD_REQUEST);
        }
        if (request.getCartId() == null || request.getCartId() <= 0) {
            throw new ServiceException("Valid cartId is required", HttpStatus.BAD_REQUEST);
        }

        DeliveryAddressDTO address = request.getDeliveryAddress();
        if (address == null) {
            throw new ServiceException("deliveryAddress is required", HttpStatus.BAD_REQUEST);
        }
        if (address.getStreet() == null || address.getStreet().isBlank()) {
            throw new ServiceException("deliveryAddress.street is required", HttpStatus.BAD_REQUEST);
        }
        if (address.getCity() == null || address.getCity().isBlank()) {
            throw new ServiceException("deliveryAddress.city is required", HttpStatus.BAD_REQUEST);
        }
        if (address.getZipcode() == null || address.getZipcode().isBlank()) {
            throw new ServiceException("deliveryAddress.zipcode is required", HttpStatus.BAD_REQUEST);
        }

        Order order = svc.placeOrder(request);

        OrderResponseDTO response = new OrderResponseDTO();
        response.setId(order.getId());
        response.setUserId(order.getUserId());
        response.setItems(order.getItems());
        response.setTotalAmount(order.getTotalAmount());
        response.setStatus(order.getPaymentStatus());
        response.setTransactionId(order.getTransactionId());
        response.setDeliveryAddress(order.getDeliveryAddress());

        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
}