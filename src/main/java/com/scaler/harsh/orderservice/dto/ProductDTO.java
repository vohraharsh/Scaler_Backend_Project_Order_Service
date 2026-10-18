package com.scaler.harsh.orderservice.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class ProductDTO {
    private Integer id;
    private String title;
    private double price;
}