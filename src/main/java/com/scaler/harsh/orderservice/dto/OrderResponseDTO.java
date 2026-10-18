package com.scaler.harsh.orderservice.dto;

import com.scaler.harsh.orderservice.model.OrderItem;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class OrderResponseDTO {
    private Integer userId;
    private List<OrderItem> items;
    private double totalAmount;
    private String status;
}