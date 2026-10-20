package com.scaler.harsh.orderservice.controller;

import com.scaler.harsh.orderservice.dto.CreateOrderRequestDTO;
import com.scaler.harsh.orderservice.dto.DeliveryAddressDTO;
import com.scaler.harsh.orderservice.dto.OrderResponseDTO;
import com.scaler.harsh.orderservice.dto.UpdateDeliveryStatusRequestDTO;
import com.scaler.harsh.orderservice.exceptions.ServiceException;
import com.scaler.harsh.orderservice.model.Order;
import com.scaler.harsh.orderservice.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

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
        return new ResponseEntity<>(convertToResponseDTO(order), HttpStatus.CREATED);
    }

    @GetMapping("/orders/{id}")
    public OrderResponseDTO getOrderById(@PathVariable("id") Integer id) {
        if (id == null || id <= 0) {
            throw new ServiceException("Invalid order id", HttpStatus.BAD_REQUEST);
        }
        return convertToResponseDTO(svc.getOrderById(id));
    }

    @GetMapping("/orders/user/{userId}")
    public List<OrderResponseDTO> getOrdersByUserId(@PathVariable("userId") Integer userId) {
        if (userId == null || userId <= 0) {
            throw new ServiceException("Invalid user id", HttpStatus.BAD_REQUEST);
        }
        List<Order> orders = svc.getOrdersByUserId(userId);
        List<OrderResponseDTO> response = new ArrayList<>();
        for (Order order : orders) {
            response.add(convertToResponseDTO(order));
        }
        return response;
    }

    @PatchMapping("/orders/{id}/status")
    public OrderResponseDTO updateDeliveryStatus(@PathVariable("id") Integer id,
                                                 @RequestBody UpdateDeliveryStatusRequestDTO request) {
        if (id == null || id <= 0) {
            throw new ServiceException("Invalid order id", HttpStatus.BAD_REQUEST);
        }
        if (request.getDeliveryStatus() == null || request.getDeliveryStatus().isBlank()) {
            throw new ServiceException("deliveryStatus is required", HttpStatus.BAD_REQUEST);
        }
        Order order = svc.updateDeliveryStatus(id, request.getDeliveryStatus());
        return convertToResponseDTO(order);
    }

    private OrderResponseDTO convertToResponseDTO(Order order) {
        OrderResponseDTO dto = new OrderResponseDTO();
        dto.setId(order.getId());
        dto.setUserId(order.getUserId());
        dto.setItems(order.getItems());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setStatus(order.getPaymentStatus());
        dto.setTransactionId(order.getTransactionId());
        dto.setDeliveryAddress(order.getDeliveryAddress());
        dto.setDeliveryStatus(order.getDeliveryStatus());
        dto.setCreatedAt(order.getCreatedAt());
        return dto;
    }
}