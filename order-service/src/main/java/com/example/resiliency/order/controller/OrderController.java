package com.example.resiliency.order.controller;

import com.example.resiliency.order.client.InventoryClient;
import com.example.resiliency.order.model.Product;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders/products/{id}")
public class OrderController {

    private final InventoryClient inventoryClient;

    public OrderController(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @GetMapping("/circuit-breaker")
    public Product circuitBreaker(@PathVariable String id) {
        return inventoryClient.getProductWithCircuitBreaker(id);
    }

    @GetMapping("/retry")
    public Product retry(@PathVariable String id) {
        return inventoryClient.getProductWithRetry(id);
    }

    @GetMapping("/rate-limiter")
    public Product rateLimiter(@PathVariable String id) {
        return inventoryClient.getProductWithRateLimiter(id);
    }

    @GetMapping("/bulkhead")
    public Product bulkhead(@PathVariable String id) {
        return inventoryClient.getProductWithBulkhead(id);
    }

    @GetMapping("/time-limiter")
    public CompletableFuture<Product> timeLimiter(@PathVariable String id) {
        return inventoryClient.getProductWithTimeLimiter(id);
    }

    @GetMapping("/combined")
    public Product combined(@PathVariable String id) {
        return inventoryClient.getProductCombined(id);
    }
}
