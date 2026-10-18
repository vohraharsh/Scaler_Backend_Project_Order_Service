package com.scaler.harsh.orderservice.model;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class OrderItem {
    private Integer productId;
    private String title;
    private Integer quantity;
    private double price;
}