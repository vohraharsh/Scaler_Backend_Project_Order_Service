package com.scaler.harsh.orderservice.model;

import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@Embeddable
public class DeliveryAddress implements Serializable {
    private String street;
    private String city;
    private String zipcode;
    private String phone;
}