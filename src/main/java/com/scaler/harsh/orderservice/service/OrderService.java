package com.scaler.harsh.orderservice.service;

import com.scaler.harsh.orderservice.dto.CreateOrderRequestDTO;
import com.scaler.harsh.orderservice.model.Order;

import java.util.List;

public interface OrderService {
    Order placeOrder(CreateOrderRequestDTO request);
    List<Order> getOrdersByUserId(Integer userId);
}