package com.scaler.harsh.orderservice.service;

import com.scaler.harsh.orderservice.dto.*;
import com.scaler.harsh.orderservice.exceptions.ServiceException;
import com.scaler.harsh.orderservice.kafka.OrderEventProducer;
import com.scaler.harsh.orderservice.model.DeliveryStatus;
import com.scaler.harsh.orderservice.model.Order;
import com.scaler.harsh.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String CART_SERVICE_URL = "http://localhost:8083";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8080";
    private static final String PAYMENT_SERVICE_URL = "http://localhost:8085";

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private OrderEventProducer orderEventProducer;

    @Mock
    private OrderRepository orderRepository;

    private OrderServiceImpl orderServiceImpl;

    private final AtomicInteger idGen = new AtomicInteger(100);

    @BeforeEach
    void setUp() {
        orderServiceImpl = new OrderServiceImpl(restTemplate, orderEventProducer, orderRepository);
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            if (order.getId() == null) {
                order.setId(idGen.getAndIncrement());
            }
            return order;
        });
    }

    // ---------- helpers ----------
    private CreateOrderRequestDTO buildRequest(Integer userId, String paymentMethod, Boolean simulateFailure) {
        CreateOrderRequestDTO request = new CreateOrderRequestDTO();
        request.setUserId(userId);
        DeliveryAddressDTO address = new DeliveryAddressDTO();
        address.setStreet("221B Baker Street");
        address.setCity("London");
        address.setZipcode("NW16XE");
        address.setPhone("1234567890");
        request.setDeliveryAddress(address);
        request.setPaymentMethod(paymentMethod);
        request.setSimulateFailure(simulateFailure);
        return request;
    }

    private UserDTO buildUser(Integer id, String username, String email) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        return user;
    }

    private CartDTO buildCart(Integer id, Integer userId, List<CartProductDTO> products) {
        CartDTO cart = new CartDTO();
        cart.setId(id);
        cart.setUserId(userId);
        cart.setProducts(products);
        return cart;
    }

    private CartProductDTO buildCartProduct(Integer productId, Integer quantity) {
        CartProductDTO cp = new CartProductDTO();
        cp.setProductId(productId);
        cp.setQuantity(quantity);
        return cp;
    }

    private ProductDTO buildProduct(Integer id, String title, double price) {
        ProductDTO product = new ProductDTO();
        product.setId(id);
        product.setTitle(title);
        product.setPrice(price);
        return product;
    }

    private PaymentResponseDTO buildPaymentResponse(Integer orderId, String status, String transactionId) {
        PaymentResponseDTO response = new PaymentResponseDTO();
        response.setOrderId(orderId);
        response.setStatus(status);
        response.setTransactionId(transactionId);
        return response;
    }

    private void stubHappyPathUserAndCart(Integer userId, UserDTO user, CartDTO cart) {
        when(restTemplate.getForEntity(USER_SERVICE_URL + "/users/" + userId, UserDTO.class))
                .thenReturn(ResponseEntity.ok(user));
        when(restTemplate.getForEntity(CART_SERVICE_URL + "/carts/active/" + userId, CartDTO.class))
                .thenReturn(ResponseEntity.ok(cart));
    }

    // ---------- placeOrder: user lookup ----------
    @Test
    void placeOrder_userNotFound_throws404() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        when(restTemplate.getForEntity(USER_SERVICE_URL + "/users/5", UserDTO.class))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void placeOrder_userServiceUnavailable_throws503() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        when(restTemplate.getForEntity(USER_SERVICE_URL + "/users/5", UserDTO.class))
                .thenThrow(new ResourceAccessException("Connection refused"));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
    }

    @Test
    void placeOrder_userResponseBodyNull_throws404() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        when(restTemplate.getForEntity(USER_SERVICE_URL + "/users/5", UserDTO.class))
                .thenReturn(ResponseEntity.ok(null));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // ---------- placeOrder: cart lookup ----------
    @Test
    void placeOrder_noActiveCart_throws404() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        when(restTemplate.getForEntity(USER_SERVICE_URL + "/users/5", UserDTO.class)).thenReturn(ResponseEntity.ok(user));
        when(restTemplate.getForEntity(CART_SERVICE_URL + "/carts/active/5", CartDTO.class))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void placeOrder_cartServiceUnavailable_throws503() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        when(restTemplate.getForEntity(USER_SERVICE_URL + "/users/5", UserDTO.class)).thenReturn(ResponseEntity.ok(user));
        when(restTemplate.getForEntity(CART_SERVICE_URL + "/carts/active/5", CartDTO.class))
                .thenThrow(new ResourceAccessException("Connection refused"));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
    }

    @Test
    void placeOrder_cartResponseBodyNull_throws404() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        when(restTemplate.getForEntity(USER_SERVICE_URL + "/users/5", UserDTO.class)).thenReturn(ResponseEntity.ok(user));
        when(restTemplate.getForEntity(CART_SERVICE_URL + "/carts/active/5", CartDTO.class)).thenReturn(ResponseEntity.ok(null));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // ---------- placeOrder: product lookup per cart item ----------
    @Test
    void placeOrder_productNotFound_throws404() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        CartDTO cart = buildCart(22, 5, List.of(buildCartProduct(10, 2)));
        stubHappyPathUserAndCart(5, user, cart);
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/10", ProductDTO.class))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void placeOrder_productServiceUnavailable_throws503() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        CartDTO cart = buildCart(22, 5, List.of(buildCartProduct(10, 2)));
        stubHappyPathUserAndCart(5, user, cart);
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/10", ProductDTO.class))
                .thenThrow(new ResourceAccessException("Connection refused"));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
    }

    @Test
    void placeOrder_productResponseBodyNull_throws404() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        CartDTO cart = buildCart(22, 5, List.of(buildCartProduct(10, 2)));
        stubHappyPathUserAndCart(5, user, cart);
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/10", ProductDTO.class)).thenReturn(ResponseEntity.ok(null));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void placeOrder_multipleItems_computesCorrectTotalAndItemList() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        CartDTO cart = buildCart(22, 5, List.of(buildCartProduct(10, 2), buildCartProduct(11, 1)));
        stubHappyPathUserAndCart(5, user, cart);
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/10", ProductDTO.class))
                .thenReturn(ResponseEntity.ok(buildProduct(10, "Phone", 100.0)));
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/11", ProductDTO.class))
                .thenReturn(ResponseEntity.ok(buildProduct(11, "Case", 20.0)));
        when(restTemplate.postForObject(eq(PAYMENT_SERVICE_URL + "/payments"), any(PaymentRequestDTO.class), eq(PaymentResponseDTO.class)))
                .thenAnswer(inv -> {
                    PaymentRequestDTO req = inv.getArgument(1);
                    return buildPaymentResponse(req.getOrderId(), "SUCCESS", "TXN-1");
                });

        Order result = orderServiceImpl.placeOrder(request);

        assertEquals(220.0, result.getTotalAmount());
        assertEquals(2, result.getItems().size());
        assertEquals(10, result.getItems().get(0).getProductId());
        assertEquals(2, result.getItems().get(0).getQuantity());
        assertEquals(100.0, result.getItems().get(0).getPrice());
    }

    // ---------- placeOrder: payment ----------
    @Test
    void placeOrder_paymentServiceUnavailable_throws503_afterOrderSavedPending() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        CartDTO cart = buildCart(22, 5, List.of(buildCartProduct(10, 1)));
        stubHappyPathUserAndCart(5, user, cart);
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/10", ProductDTO.class))
                .thenReturn(ResponseEntity.ok(buildProduct(10, "Phone", 100.0)));
        when(restTemplate.postForObject(eq(PAYMENT_SERVICE_URL + "/payments"), any(PaymentRequestDTO.class), eq(PaymentResponseDTO.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        verify(orderRepository, times(1)).save(any(Order.class));
        verifyNoInteractions(orderEventProducer);
    }

    @Test
    void placeOrder_paymentResponseNull_throws400() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        CartDTO cart = buildCart(22, 5, List.of(buildCartProduct(10, 1)));
        stubHappyPathUserAndCart(5, user, cart);
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/10", ProductDTO.class))
                .thenReturn(ResponseEntity.ok(buildProduct(10, "Phone", 100.0)));
        when(restTemplate.postForObject(eq(PAYMENT_SERVICE_URL + "/payments"), any(PaymentRequestDTO.class), eq(PaymentResponseDTO.class)))
                .thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void placeOrder_paymentDeclined_throws402_savesFailedStatus_doesNotPublishOrDeleteCart() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", true);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        CartDTO cart = buildCart(22, 5, List.of(buildCartProduct(10, 1)));
        stubHappyPathUserAndCart(5, user, cart);
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/10", ProductDTO.class))
                .thenReturn(ResponseEntity.ok(buildProduct(10, "Phone", 100.0)));
        when(restTemplate.postForObject(eq(PAYMENT_SERVICE_URL + "/payments"), any(PaymentRequestDTO.class), eq(PaymentResponseDTO.class)))
                .thenAnswer(inv -> {
                    PaymentRequestDTO req = inv.getArgument(1);
                    return buildPaymentResponse(req.getOrderId(), "FAILED", "TXN-9");
                });

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.placeOrder(request));
        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());

        verify(orderRepository, times(2)).save(any(Order.class));
        verifyNoInteractions(orderEventProducer);
        verify(restTemplate, never()).delete(anyString());
    }

    @Test
    void placeOrder_paymentSuccess_publishesEventAndDeletesCart() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        CartDTO cart = buildCart(22, 5, List.of(buildCartProduct(10, 1)));
        stubHappyPathUserAndCart(5, user, cart);
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/10", ProductDTO.class))
                .thenReturn(ResponseEntity.ok(buildProduct(10, "Phone", 100.0)));
        when(restTemplate.postForObject(eq(PAYMENT_SERVICE_URL + "/payments"), any(PaymentRequestDTO.class), eq(PaymentResponseDTO.class)))
                .thenAnswer(inv -> {
                    PaymentRequestDTO req = inv.getArgument(1);
                    return buildPaymentResponse(req.getOrderId(), "SUCCESS", "TXN-1");
                });

        Order result = orderServiceImpl.placeOrder(request);

        assertEquals("SUCCESS", result.getPaymentStatus());
        assertEquals("TXN-1", result.getTransactionId());

        ArgumentCaptor<Integer> orderIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(orderEventProducer).publishOrderPlaced(orderIdCaptor.capture(), eq(5), eq("alice@example.com"), eq(100.0));
        assertEquals(result.getId(), orderIdCaptor.getValue());

        verify(restTemplate).delete(CART_SERVICE_URL + "/carts/22");
    }

    @Test
    void placeOrder_cartDeletionFails_doesNotPropagateException() {
        CreateOrderRequestDTO request = buildRequest(5, "CARD", false);
        UserDTO user = buildUser(5, "alice", "alice@example.com");
        CartDTO cart = buildCart(22, 5, List.of(buildCartProduct(10, 1)));
        stubHappyPathUserAndCart(5, user, cart);
        when(restTemplate.getForEntity(PRODUCT_SERVICE_URL + "/products/10", ProductDTO.class))
                .thenReturn(ResponseEntity.ok(buildProduct(10, "Phone", 100.0)));
        when(restTemplate.postForObject(eq(PAYMENT_SERVICE_URL + "/payments"), any(PaymentRequestDTO.class), eq(PaymentResponseDTO.class)))
                .thenAnswer(inv -> {
                    PaymentRequestDTO req = inv.getArgument(1);
                    return buildPaymentResponse(req.getOrderId(), "SUCCESS", "TXN-1");
                });
        doThrow(new ResourceAccessException("Cart Service unreachable")).when(restTemplate).delete(anyString());

        Order result = assertDoesNotThrow(() -> orderServiceImpl.placeOrder(request));
        assertEquals("SUCCESS", result.getPaymentStatus());
        verify(orderEventProducer).publishOrderPlaced(any(), any(), any(), any(Double.class));
    }

    // ---------- getOrdersByUserId ----------
    @Test
    void getOrdersByUserId_delegatesToRepository() {
        Order o1 = new Order();
        o1.setId(1);
        when(orderRepository.findByUserIdOrderByIdDesc(5)).thenReturn(List.of(o1));

        List<Order> result = orderServiceImpl.getOrdersByUserId(5);

        assertEquals(1, result.size());
        verify(orderRepository).findByUserIdOrderByIdDesc(5);
    }

    // ---------- getOrderById ----------
    @Test
    void getOrderById_found_returnsOrder() {
        Order order = new Order();
        order.setId(7);
        when(orderRepository.findById(7)).thenReturn(Optional.of(order));

        Order result = orderServiceImpl.getOrderById(7);

        assertEquals(7, result.getId());
    }

    @Test
    void getOrderById_notFound_throws404() {
        when(orderRepository.findById(999)).thenReturn(Optional.empty());

        ServiceException ex = assertThrows(ServiceException.class, () -> orderServiceImpl.getOrderById(999));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // ---------- updateDeliveryStatus ----------
    @Test
    void updateDeliveryStatus_orderNotFound_throws404() {
        when(orderRepository.findById(999)).thenReturn(Optional.empty());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderServiceImpl.updateDeliveryStatus(999, "SHIPPED"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void updateDeliveryStatus_invalidStatusString_throws400() {
        Order order = new Order();
        order.setId(1);
        order.setDeliveryStatus(DeliveryStatus.PLACED);
        when(orderRepository.findById(1)).thenReturn(Optional.of(order));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderServiceImpl.updateDeliveryStatus(1, "NOT_A_REAL_STATUS"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void updateDeliveryStatus_alreadyDelivered_throws400() {
        Order order = new Order();
        order.setId(1);
        order.setDeliveryStatus(DeliveryStatus.DELIVERED);
        when(orderRepository.findById(1)).thenReturn(Optional.of(order));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderServiceImpl.updateDeliveryStatus(1, "SHIPPED"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void updateDeliveryStatus_alreadyCancelled_throws400() {
        Order order = new Order();
        order.setId(1);
        order.setDeliveryStatus(DeliveryStatus.CANCELLED);
        when(orderRepository.findById(1)).thenReturn(Optional.of(order));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderServiceImpl.updateDeliveryStatus(1, "SHIPPED"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void updateDeliveryStatus_backwardMove_throws400() {
        Order order = new Order();
        order.setId(1);
        order.setDeliveryStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        when(orderRepository.findById(1)).thenReturn(Optional.of(order));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderServiceImpl.updateDeliveryStatus(1, "SHIPPED"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void updateDeliveryStatus_sameValueMove_throws400() {
        Order order = new Order();
        order.setId(1);
        order.setDeliveryStatus(DeliveryStatus.SHIPPED);
        when(orderRepository.findById(1)).thenReturn(Optional.of(order));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderServiceImpl.updateDeliveryStatus(1, "SHIPPED"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void updateDeliveryStatus_validForwardMove_savesAndReturns() {
        Order order = new Order();
        order.setId(1);
        order.setDeliveryStatus(DeliveryStatus.PLACED);
        when(orderRepository.findById(1)).thenReturn(Optional.of(order));

        Order result = orderServiceImpl.updateDeliveryStatus(1, "SHIPPED");

        assertEquals(DeliveryStatus.SHIPPED, result.getDeliveryStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void updateDeliveryStatus_toCancelled_allowedFromAnyNonTerminalState() {
        Order order = new Order();
        order.setId(1);
        order.setDeliveryStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        when(orderRepository.findById(1)).thenReturn(Optional.of(order));

        Order result = orderServiceImpl.updateDeliveryStatus(1, "CANCELLED");

        assertEquals(DeliveryStatus.CANCELLED, result.getDeliveryStatus());
    }

    @Test
    void updateDeliveryStatus_trimmedAndLowercasedInput_isNormalized() {
        Order order = new Order();
        order.setId(1);
        order.setDeliveryStatus(DeliveryStatus.PLACED);
        when(orderRepository.findById(1)).thenReturn(Optional.of(order));

        Order result = orderServiceImpl.updateDeliveryStatus(1, "  shipped  ");

        assertEquals(DeliveryStatus.SHIPPED, result.getDeliveryStatus());
    }
}