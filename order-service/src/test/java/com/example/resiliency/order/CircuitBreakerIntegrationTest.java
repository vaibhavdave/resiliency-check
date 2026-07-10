package com.example.resiliency.order;

import static com.example.resiliency.order.support.MockDownstream.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.resiliency.order.client.InventoryClient;
import com.example.resiliency.order.model.Product;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "resilience4j.circuitbreaker.instances.inventoryService.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.inventoryService.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.inventoryService.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.inventoryService.wait-duration-in-open-state=5s"
})
class CircuitBreakerIntegrationTest {

    static MockWebServer mockWebServer;

    @BeforeAll
    static void startServer() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        mockWebServer.shutdown();
    }

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("inventory.base-url", () -> mockWebServer.url("/").toString());
    }

    @Autowired
    private InventoryClient inventoryClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    void opensAfterFailureThresholdAndShortCircuitsSubsequentCalls() {
        for (int i = 0; i < 4; i++) {
            mockWebServer.enqueue(serverError());
        }
        for (int i = 0; i < 4; i++) {
            inventoryClient.getProductWithCircuitBreaker("1");
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventoryService");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestsSoFar = mockWebServer.getRequestCount();
        Product result = inventoryClient.getProductWithCircuitBreaker("1");

        assertThat(result.id()).startsWith("FALLBACK");
        assertThat(mockWebServer.getRequestCount())
                .as("an open circuit must not let the call reach the downstream service")
                .isEqualTo(requestsSoFar);
    }
}
