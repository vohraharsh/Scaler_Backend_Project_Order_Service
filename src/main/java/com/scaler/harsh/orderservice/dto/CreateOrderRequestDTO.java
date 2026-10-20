package com.scaler.harsh.orderservice.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CreateOrderRequestDTO {
    private Integer userId;
    private DeliveryAddressDTO deliveryAddress;
    private String paymentMethod;
    private Boolean simulateFailure;
}