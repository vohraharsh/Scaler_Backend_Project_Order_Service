package com.scaler.harsh.orderservice.service;

import com.scaler.harsh.orderservice.dto.*;
import com.scaler.harsh.orderservice.exceptions.ServiceException;
import com.scaler.harsh.orderservice.kafka.OrderEventProducer;
import com.scaler.harsh.orderservice.model.DeliveryAddress;
import com.scaler.harsh.orderservice.model.DeliveryStatus;
import com.scaler.harsh.orderservice.model.Order;
import com.scaler.harsh.orderservice.model.OrderItem;
import com.scaler.harsh.orderservice.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class OrderServiceImpl implements OrderService {

    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String CART_SERVICE_URL = "http://localhost:8083";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8080";
    private static final String PAYMENT_SERVICE_URL = "http://localhost:8085";

    private final RestTemplate restTemplate;
    private final OrderEventProducer orderEventProducer;
    private final OrderRepository orderRepository;

    public OrderServiceImpl(RestTemplate restTemplate, OrderEventProducer orderEventProducer, OrderRepository orderRepository) {
        this.restTemplate = restTemplate;
        this.orderEventProducer = orderEventProducer;
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public Order placeOrder(CreateOrderRequestDTO request) {

        UserDTO user;
        try {
            ResponseEntity<UserDTO> userResponse = restTemplate.getForEntity(
                    USER_SERVICE_URL + "/users/" + request.getUserId(), UserDTO.class);
            user = userResponse.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ServiceException("User not found: " + request.getUserId(), HttpStatus.NOT_FOUND);
        } catch (RestClientException e) {
            throw new ServiceException("User Service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (user == null) {
            throw new ServiceException("User not found: " + request.getUserId(), HttpStatus.NOT_FOUND);
        }

        CartDTO cart;
        try {
            ResponseEntity<CartDTO> cartResponse = restTemplate.getForEntity(
                    CART_SERVICE_URL + "/carts/" + request.getCartId(), CartDTO.class);
            cart = cartResponse.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ServiceException("Cart not found: " + request.getCartId(), HttpStatus.NOT_FOUND);
        } catch (RestClientException e) {
            throw new ServiceException("Cart Service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (cart == null) {
            throw new ServiceException("Cart not found: " + request.getCartId(), HttpStatus.NOT_FOUND);
        }

        List<OrderItem> items = new ArrayList<>();
        double total = 0;

        for (CartProductDTO cartProduct : cart.getProducts()) {
            ProductDTO product;
            try {
                ResponseEntity<ProductDTO> productResponse = restTemplate.getForEntity(
                        PRODUCT_SERVICE_URL + "/products/" + cartProduct.getProductId(), ProductDTO.class);
                product = productResponse.getBody();
            } catch (HttpClientErrorException.NotFound e) {
                throw new ServiceException("Product not found: " + cartProduct.getProductId(), HttpStatus.NOT_FOUND);
            } catch (RestClientException e) {
                throw new ServiceException("Product Service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
            }

            if (product == null) {
                throw new ServiceException("Product not found: " + cartProduct.getProductId(), HttpStatus.NOT_FOUND);
            }

            OrderItem item = new OrderItem();
            item.setProductId(product.getId());
            item.setTitle(product.getTitle());
            item.setQuantity(cartProduct.getQuantity());
            item.setPrice(product.getPrice());
            items.add(item);

            total += product.getPrice() * cartProduct.getQuantity();
        }

        DeliveryAddressDTO addressRequest = request.getDeliveryAddress();
        DeliveryAddress deliveryAddress = new DeliveryAddress();
        deliveryAddress.setStreet(addressRequest.getStreet());
        deliveryAddress.setCity(addressRequest.getCity());
        deliveryAddress.setZipcode(addressRequest.getZipcode());
        deliveryAddress.setPhone(addressRequest.getPhone());

        // Save order in a pending state first — DB assigns the real id here
        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setItems(items);
        order.setTotalAmount(total);
        order.setPaymentStatus("PENDING");
        order.setDeliveryAddress(deliveryAddress);
        order.setDeliveryStatus(DeliveryStatus.PLACED);
        order = orderRepository.save(order);

        PaymentRequestDTO paymentRequest = new PaymentRequestDTO();
        paymentRequest.setOrderId(order.getId());
        paymentRequest.setUserId(request.getUserId());
        paymentRequest.setAmount(total);
        paymentRequest.setPaymentMethod(request.getPaymentMethod());
        paymentRequest.setSimulateFailure(request.getSimulateFailure());

        PaymentResponseDTO payment;
        try {
            payment = restTemplate.postForObject(
                    PAYMENT_SERVICE_URL + "/payments", paymentRequest, PaymentResponseDTO.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.PAYMENT_REQUIRED) {
                // Payment Service returns 402 for a declined payment, with the decline details in the body.
                payment = parsePaymentResponse(e);
            } else {
                throw new ServiceException("Payment Service rejected the request: " + e.getMessage(),
                        HttpStatus.BAD_REQUEST);
            }
        } catch (RestClientException e) {
            throw new ServiceException("Payment Service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (payment == null) {
            throw new ServiceException("Payment failed", HttpStatus.BAD_REQUEST);
        }

        // Record the payment result either way — a declined payment is still a real, auditable attempt.
        order.setPaymentStatus(payment.getStatus());
        order.setTransactionId(payment.getTransactionId());
        order = orderRepository.save(order);

        if (!"SUCCESS".equals(payment.getStatus())) {
            // Payment declined: do NOT clear the cart (items stay available to retry) and do NOT
            // publish an order-placed event. The order row stays as a FAILED audit record.
            throw new ServiceException(
                    "Payment declined for order " + order.getId() + " (transaction " + payment.getTransactionId() + ")",
                    HttpStatus.PAYMENT_REQUIRED);
        }

        orderEventProducer.publishOrderPlaced(order.getId(), request.getUserId(), total);

        // Order is placed and paid — clear the cart so it can't be re-ordered or re-charged.
        // A failure here is a cleanup problem, not a reason to fail an already-completed order.
        try {
            restTemplate.delete(CART_SERVICE_URL + "/carts/" + request.getCartId());
        } catch (RestClientException e) {
            System.err.println("Failed to delete cart " + request.getCartId()
                    + " after order " + order.getId() + ": " + e.getMessage());
        }

        return order;
    }

    private PaymentResponseDTO parsePaymentResponse(HttpClientErrorException e) {
        try {
            return e.getResponseBodyAs(PaymentResponseDTO.class);
        } catch (Exception parseEx) {
            return null;
        }
    }

    @Override
    public List<Order> getOrdersByUserId(Integer userId) {
        return orderRepository.findByUserIdOrderByIdDesc(userId);
    }

    @Override
    public Order getOrderById(Integer orderId) {
        Optional<Order> order = orderRepository.findById(orderId);
        if (order.isEmpty()) {
            throw new ServiceException("Order not found: " + orderId, HttpStatus.NOT_FOUND);
        }
        return order.get();
    }

    @Override
    public Order updateDeliveryStatus(Integer orderId, String deliveryStatus) {
        Optional<Order> existing = orderRepository.findById(orderId);
        if (existing.isEmpty()) {
            throw new ServiceException("Order not found: " + orderId, HttpStatus.NOT_FOUND);
        }

        DeliveryStatus parsed;
        try {
            parsed = DeliveryStatus.valueOf(deliveryStatus.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ServiceException(
                    "Invalid deliveryStatus. Must be one of: PLACED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED",
                    HttpStatus.BAD_REQUEST);
        }

        Order order = existing.get();
        DeliveryStatus current = order.getDeliveryStatus();

        if (current == DeliveryStatus.DELIVERED || current == DeliveryStatus.CANCELLED) {
            throw new ServiceException(
                    "Order is already " + current + " and its delivery status cannot be changed further",
                    HttpStatus.BAD_REQUEST);
        }

        if (parsed != DeliveryStatus.CANCELLED) {
            int currentRank = (current == null) ? -1 : forwardRank(current);
            int targetRank = forwardRank(parsed);
            if (targetRank <= currentRank) {
                throw new ServiceException(
                        "Cannot move delivery status backward (or to the same value): "
                                + (current == null ? "PLACED" : current) + " -> " + parsed,
                        HttpStatus.BAD_REQUEST);
            }
        }

        order.setDeliveryStatus(parsed);
        return orderRepository.save(order);
    }

    private int forwardRank(DeliveryStatus status) {
        switch (status) {
            case PLACED: return 0;
            case SHIPPED: return 1;
            case OUT_FOR_DELIVERY: return 2;
            case DELIVERED: return 3;
            default: return Integer.MAX_VALUE;
        }
    }
}