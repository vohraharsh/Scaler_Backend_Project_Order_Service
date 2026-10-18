package com.scaler.harsh.orderservice.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class Order {
    private Integer userId;
    private List<OrderItem> items;
    private double totalAmount;
}