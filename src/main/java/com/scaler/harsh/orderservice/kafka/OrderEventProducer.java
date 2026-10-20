package com.scaler.harsh.orderservice.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderEventProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishOrderPlaced(Integer orderId, Integer userId, String userEmail, double totalAmount) {
        // Formatted as a real email body would be — Notification Service renders this as-is.
        // Still a mock: nothing here actually sends mail.
        String message = "To: " + userEmail + "\n"
                + "Subject: Order Confirmation - Order #" + orderId + "\n\n"
                + "Hi,\n\n"
                + "Your order has been placed successfully.\n"
                + "Order ID: " + orderId + "\n"
                + "Total Amount: $" + totalAmount + "\n\n"
                + "Thank you for shopping with us!";
        kafkaTemplate.send("order-placed", message);
    }
}