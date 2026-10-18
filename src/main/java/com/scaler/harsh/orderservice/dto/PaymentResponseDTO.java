package com.scaler.harsh.orderservice.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class PaymentResponseDTO {
    private String transactionId;
    private Integer orderId;
    private double amount;
    private String status;
}