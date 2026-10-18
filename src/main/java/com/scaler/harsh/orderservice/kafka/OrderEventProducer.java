package com.scaler.harsh.orderservice.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderEventProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishOrderPlaced(Integer orderId, Integer userId, double totalAmount) {
        String message = "Order " + orderId + " placed by user " + userId + " for amount " + totalAmount;
        kafkaTemplate.send("order-placed", message);
    }
}