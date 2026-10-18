package com.scaler.harsh.orderservice.service;

import com.scaler.harsh.orderservice.dto.*;
import com.scaler.harsh.orderservice.exceptions.ServiceException;
import com.scaler.harsh.orderservice.model.Order;
import com.scaler.harsh.orderservice.model.OrderItem;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class OrderServiceImpl implements OrderService {

    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String CART_SERVICE_URL = "http://localhost:8083";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8080";

    private final RestTemplate restTemplate;

    public OrderServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
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

        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setItems(items);
        order.setTotalAmount(total);
        return order;
    }
}