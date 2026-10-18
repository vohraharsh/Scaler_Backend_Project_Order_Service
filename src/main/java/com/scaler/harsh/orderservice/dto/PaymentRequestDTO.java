package com.scaler.harsh.orderservice.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class PaymentRequestDTO {
    private Integer orderId;
    private Integer userId;
    private double amount;
}