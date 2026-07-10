package com.example.resiliency.order;

import static com.example.resiliency.order.support.MockDownstream.successDelayedBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.resiliency.order.client.InventoryClient;
import com.example.resiliency.order.model.Product;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "resilience4j.timelimiter.instances.inventoryService.timeout-duration=500ms",
        "resilience4j.timelimiter.instances.inventoryService.cancel-running-future=true"
})
class TimeLimiterIntegrationTest {

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
    void fallsBackWhenDownstreamExceedsTheTimeout() throws Exception {
        mockWebServer.enqueue(successDelayedBy(3, TimeUnit.SECONDS));

        CompletableFuture<Product> future = inventoryClient.getProductWithTimeLimiter("1");
        Product product = future.get(5, TimeUnit.SECONDS);

        assertThat(product.id()).startsWith("FALLBACK");
    }

    @Test
    void succeedsWhenDownstreamRespondsInTime() throws Exception {
        mockWebServer.enqueue(successDelayedBy(50, TimeUnit.MILLISECONDS));

        CompletableFuture<Product> future = inventoryClient.getProductWithTimeLimiter("1");
        Product product = future.get(5, TimeUnit.SECONDS);

        assertThat(product.id()).isEqualTo("1");
    }
}
