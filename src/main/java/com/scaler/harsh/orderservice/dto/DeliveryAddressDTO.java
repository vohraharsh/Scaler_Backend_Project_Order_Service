package com.scaler.harsh.orderservice.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeliveryAddressDTO {
    private String street;
    private String city;
    private String zipcode;
    private String phone;
}