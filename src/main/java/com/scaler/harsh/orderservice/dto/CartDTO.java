package com.scaler.harsh.orderservice.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class CartDTO {
    private Integer id;
    private Integer userId;
    private List<CartProductDTO> products;
}