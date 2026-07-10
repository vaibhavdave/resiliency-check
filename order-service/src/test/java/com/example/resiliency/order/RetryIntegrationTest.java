package com.example.resiliency.order;

import static com.example.resiliency.order.support.MockDownstream.serverError;
import static com.example.resiliency.order.support.MockDownstream.success;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.resiliency.order.client.InventoryClient;
import com.example.resiliency.order.model.Product;
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
        "resilience4j.retry.instances.inventoryService.max-attempts=3",
        "resilience4j.retry.instances.inventoryService.wait-duration=50ms",
        "resilience4j.retry.instances.inventoryService.enable-exponential-backoff=false"
})
class RetryIntegrationTest {

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

    @Test
    void retriesTransientFailuresThenSucceeds() {
        int before = mockWebServer.getRequestCount();
        mockWebServer.enqueue(serverError());
        mockWebServer.enqueue(serverError());
        mockWebServer.enqueue(success());

        Product product = inventoryClient.getProductWithRetry("1");

        assertThat(product.id()).isEqualTo("1");
        assertThat(product.name()).isEqualTo("Wireless Mouse");
        assertThat(mockWebServer.getRequestCount() - before).isEqualTo(3);
    }

    @Test
    void fallsBackOnceAttemptsAreExhausted() {
        int before = mockWebServer.getRequestCount();
        mockWebServer.enqueue(serverError());
        mockWebServer.enqueue(serverError());
        mockWebServer.enqueue(serverError());

        Product product = inventoryClient.getProductWithRetry("1");

        assertThat(product.id()).startsWith("FALLBACK");
        assertThat(mockWebServer.getRequestCount() - before).isEqualTo(3);
    }
}
