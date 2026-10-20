package com.scaler.harsh.orderservice.dto;

import com.scaler.harsh.orderservice.model.DeliveryAddress;
import com.scaler.harsh.orderservice.model.DeliveryStatus;
import com.scaler.harsh.orderservice.model.OrderItem;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter @Setter
public class OrderResponseDTO {
    private Integer id;
    private Integer userId;
    private List<OrderItem> items;
    private double totalAmount;
    private String status;
    private String transactionId;
    private DeliveryAddress deliveryAddress;
    private DeliveryStatus deliveryStatus;
    private LocalDateTime createdAt;
}