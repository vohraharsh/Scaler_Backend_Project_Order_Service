package com.scaler.harsh.orderservice.model;

import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@Embeddable
public class OrderItem implements Serializable {
    private Integer productId;
    private String title;
    private Integer quantity;
    private double price;
}