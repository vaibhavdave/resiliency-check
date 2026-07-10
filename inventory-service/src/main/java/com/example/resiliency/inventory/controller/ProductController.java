package com.example.resiliency.inventory.controller;

import com.example.resiliency.inventory.failure.FailureMode;
import com.example.resiliency.inventory.failure.FailureState;
import com.example.resiliency.inventory.failure.FailureState.FailureSettings;
import com.example.resiliency.inventory.model.Product;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stands in for a downstream dependency that is not always well-behaved.
 * Behavior is controlled either process-wide (see AdminController /admin/mode) or,
 * for quick manual testing, per-request via query parameters that override the
 * process-wide setting for that single call only.
 */
@RestController
public class ProductController {

    private static final Map<String, Product> CATALOG = Map.of(
            "1", new Product("1", "Wireless Mouse", 42, 19.99),
            "2", new Product("2", "Mechanical Keyboard", 17, 79.99),
            "3", new Product("3", "USB-C Hub", 5, 34.50)
    );

    private final FailureState failureState;

    public ProductController(FailureState failureState) {
        this.failureState = failureState;
    }

    @GetMapping("/products/{id}")
    public Product getProduct(
            @PathVariable String id,
            @RequestParam(required = false) FailureMode mode,
            @RequestParam(required = false) Long delayMs,
            @RequestParam(required = false) Double errorRate) {

        applyFailureBehavior(resolveSettings(mode, delayMs, errorRate));

        Product product = CATALOG.get(id);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such product: " + id);
        }
        return product;
    }

    private FailureSettings resolveSettings(FailureMode mode, Long delayMs, Double errorRate) {
        if (mode == null) {
            return failureState.get();
        }
        long resolvedDelay = delayMs != null ? delayMs : defaultDelayFor(mode);
        double resolvedRate = errorRate != null ? errorRate : 0.5;
        return new FailureSettings(mode, resolvedDelay, resolvedRate);
    }

    private long defaultDelayFor(FailureMode mode) {
        return switch (mode) {
            case SLOW -> 3000L;
            case TIMEOUT -> 15000L;
            default -> 0L;
        };
    }

    private void applyFailureBehavior(FailureSettings settings) {
        switch (settings.mode()) {
            case OK -> { /* no-op, respond normally */ }
            case ERROR -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "inventory-service: simulated failure");
            case SLOW, TIMEOUT -> sleep(settings.delayMs());
            case RANDOM -> {
                if (ThreadLocalRandom.current().nextDouble() < settings.errorRate()) {
                    throw new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "inventory-service: simulated random failure");
                }
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "interrupted");
        }
    }
}
