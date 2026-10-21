package com.scaler.harsh.orderservice.service;

import com.scaler.harsh.orderservice.dto.*;
import com.scaler.harsh.orderservice.exceptions.ServiceException;
import com.scaler.harsh.orderservice.kafka.OrderEventProducer;
import com.scaler.harsh.orderservice.model.DeliveryAddress;
import com.scaler.harsh.orderservice.model.DeliveryStatus;
import com.scaler.harsh.orderservice.model.Order;
import com.scaler.harsh.orderservice.model.OrderItem;
import com.scaler.harsh.orderservice.repository.OrderRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
    private final HttpServletRequest httpServletRequest;

    public OrderServiceImpl(RestTemplate restTemplate, OrderEventProducer orderEventProducer,
                            OrderRepository orderRepository, HttpServletRequest httpServletRequest) {
        this.restTemplate = restTemplate;
        this.orderEventProducer = orderEventProducer;
        this.orderRepository = orderRepository;
        this.httpServletRequest = httpServletRequest;
    }

    // User/Cart/Payment Service now each enforce their own JWT check (gap #3), so the incoming
    // request's own Authorization header has to be forwarded on every internal call, or these
    // calls start failing with 401 the moment those filters go live. Product Service stays
    // public (matches kong.yml — no jwt plugin on its route), so forwarding there is harmless,
    // not required; done anyway so every outgoing call follows one consistent helper.
    private HttpEntity<Void> authEntity() {
        return new HttpEntity<>(authHeaders());
    }

    private <T> HttpEntity<T> authEntity(T body) {
        return new HttpEntity<>(body, authHeaders());
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String token = httpServletRequest.getHeader("Authorization");
        if (token != null) {
            headers.set("Authorization", token);
        }
        return headers;
    }

    @Override
    @Transactional(noRollbackFor = ServiceException.class)
    public Order placeOrder(CreateOrderRequestDTO request) {

        UserDTO user;
        try {
            ResponseEntity<UserDTO> userResponse = restTemplate.exchange(
                    USER_SERVICE_URL + "/users/" + request.getUserId(),
                    HttpMethod.GET, authEntity(), UserDTO.class);
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
            ResponseEntity<CartDTO> cartResponse = restTemplate.exchange(
                    CART_SERVICE_URL + "/carts/active/" + request.getUserId(),
                    HttpMethod.GET, authEntity(), CartDTO.class);
            cart = cartResponse.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ServiceException("No active cart for user: " + request.getUserId(), HttpStatus.NOT_FOUND);
        } catch (RestClientException e) {
            throw new ServiceException("Cart Service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (cart == null) {
            throw new ServiceException("No active cart for user: " + request.getUserId(), HttpStatus.NOT_FOUND);
        }

        List<OrderItem> items = new ArrayList<>();
        double total = 0;

        for (CartProductDTO cartProduct : cart.getProducts()) {
            ProductDTO product;
            try {
                ResponseEntity<ProductDTO> productResponse = restTemplate.exchange(
                        PRODUCT_SERVICE_URL + "/products/" + cartProduct.getProductId(),
                        HttpMethod.GET, authEntity(), ProductDTO.class);
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

        // Payment Service always returns 201 here — processing the request succeeded even if the
        // outcome is a decline. The outcome lives in payment.getStatus(), not the HTTP status.
        PaymentResponseDTO payment;
        try {
            ResponseEntity<PaymentResponseDTO> paymentResponse = restTemplate.exchange(
                    PAYMENT_SERVICE_URL + "/payments",
                    HttpMethod.POST, authEntity(paymentRequest), PaymentResponseDTO.class);
            payment = paymentResponse.getBody();
        } catch (RestClientException e) {
            throw new ServiceException("Payment Service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (payment == null) {
            throw new ServiceException("Payment failed", HttpStatus.BAD_REQUEST);
        }

        order.setPaymentStatus(payment.getStatus());
        order.setTransactionId(payment.getTransactionId());
        order = orderRepository.save(order);

        if (!"SUCCESS".equals(payment.getStatus())) {
            throw new ServiceException(
                    "Payment declined for order " + order.getId() + " (transaction " + payment.getTransactionId() + ")",
                    HttpStatus.PAYMENT_REQUIRED);
        }

        orderEventProducer.publishOrderPlaced(order.getId(), request.getUserId(), user.getEmail(), total);

        // Order is placed and paid — clear the cart so it can't be re-ordered or re-charged.
        // A failure here is a cleanup problem, not a reason to fail an already-completed order.
        try {
            restTemplate.exchange(
                    CART_SERVICE_URL + "/carts/" + cart.getId(),
                    HttpMethod.DELETE, authEntity(), Void.class);
        } catch (RestClientException e) {
            System.err.println("Failed to delete cart " + cart.getId()
                    + " after order " + order.getId() + ": " + e.getMessage());
        }

        return order;
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