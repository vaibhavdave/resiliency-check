package com.example.resiliency.order.client;

import com.example.resiliency.order.model.Product;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Talks to inventory-service, each method demonstrating a single Resilience4j module
 * (plus one endpoint stacking several together) so each pattern can be exercised in isolation.
 */
@Service
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestClient restClient;
    private final Executor inventoryAsyncExecutor;

    public InventoryClient(RestClient inventoryRestClient, Executor inventoryAsyncExecutor) {
        this.restClient = inventoryRestClient;
        this.inventoryAsyncExecutor = inventoryAsyncExecutor;
    }

    @CircuitBreaker(name = "inventoryService", fallbackMethod = "fallbackProduct")
    public Product getProductWithCircuitBreaker(String id) {
        return fetchProduct(id);
    }

    @Retry(name = "inventoryService", fallbackMethod = "fallbackProduct")
    public Product getProductWithRetry(String id) {
        return fetchProduct(id);
    }

    @RateLimiter(name = "inventoryService", fallbackMethod = "fallbackProduct")
    public Product getProductWithRateLimiter(String id) {
        return fetchProduct(id);
    }

    @Bulkhead(name = "inventoryService", fallbackMethod = "fallbackProduct")
    public Product getProductWithBulkhead(String id) {
        return fetchProduct(id);
    }

    @TimeLimiter(name = "inventoryService", fallbackMethod = "fallbackProductAsync")
    public CompletableFuture<Product> getProductWithTimeLimiter(String id) {
        return CompletableFuture.supplyAsync(() -> fetchProduct(id), inventoryAsyncExecutor);
    }

    /**
     * Stacks Retry, CircuitBreaker, RateLimiter and Bulkhead on one call - the realistic case.
     * Default Resilience4j Spring AOP order (outer to inner) is:
     * Retry -&gt; CircuitBreaker -&gt; RateLimiter -&gt; TimeLimiter -&gt; Bulkhead.
     */
    @Retry(name = "inventoryService", fallbackMethod = "fallbackProduct")
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "fallbackProduct")
    @RateLimiter(name = "inventoryService", fallbackMethod = "fallbackProduct")
    @Bulkhead(name = "inventoryService", fallbackMethod = "fallbackProduct")
    public Product getProductCombined(String id) {
        return fetchProduct(id);
    }

    private Product fetchProduct(String id) {
        return restClient.get()
                .uri("/products/{id}", id)
                .retrieve()
                .body(Product.class);
    }

    private Product fallbackProduct(String id, Throwable t) {
        log.warn("Falling back for product {} due to {}: {}", id, t.getClass().getSimpleName(), t.getMessage());
        return new Product("FALLBACK-" + id, "temporarily unavailable (" + t.getClass().getSimpleName() + ")", 0, 0.0);
    }

    private CompletableFuture<Product> fallbackProductAsync(String id, Throwable t) {
        return CompletableFuture.completedFuture(fallbackProduct(id, t));
    }
}
