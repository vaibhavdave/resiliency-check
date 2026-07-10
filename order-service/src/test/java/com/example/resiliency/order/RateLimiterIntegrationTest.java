package com.example.resiliency.order;

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
        "resilience4j.ratelimiter.instances.inventoryService.limit-for-period=2",
        "resilience4j.ratelimiter.instances.inventoryService.limit-refresh-period=10s",
        "resilience4j.ratelimiter.instances.inventoryService.timeout-duration=0s"
})
class RateLimiterIntegrationTest {

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
    void rejectsCallsBeyondThePermittedRateWithoutWaiting() {
        mockWebServer.enqueue(success());
        mockWebServer.enqueue(success());

        Product first = inventoryClient.getProductWithRateLimiter("1");
        Product second = inventoryClient.getProductWithRateLimiter("1");
        Product third = inventoryClient.getProductWithRateLimiter("1");

        assertThat(first.id()).isEqualTo("1");
        assertThat(second.id()).isEqualTo("1");
        assertThat(third.id())
                .as("third call within the same period must be rejected by the rate limiter")
                .startsWith("FALLBACK");
    }
}
