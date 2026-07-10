package com.example.resiliency.order;

import static com.example.resiliency.order.support.MockDownstream.successDelayedBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.resiliency.order.client.InventoryClient;
import com.example.resiliency.order.model.Product;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        "resilience4j.bulkhead.instances.inventoryService.max-concurrent-calls=2",
        "resilience4j.bulkhead.instances.inventoryService.max-wait-duration=0s"
})
class BulkheadIntegrationTest {

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
    void rejectsCallsBeyondTheConcurrencyLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockWebServer.enqueue(successDelayedBy(1, TimeUnit.SECONDS));
        }

        ExecutorService callers = Executors.newFixedThreadPool(3);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Product>> futures = List.of(
                    callers.submit(() -> callAfter(startGate)),
                    callers.submit(() -> callAfter(startGate)),
                    callers.submit(() -> callAfter(startGate)));

            startGate.countDown();

            long fallbackCount = 0;
            for (Future<Product> future : futures) {
                Product product = future.get(5, TimeUnit.SECONDS);
                if (product.id().startsWith("FALLBACK")) {
                    fallbackCount++;
                }
            }

            assertThat(fallbackCount)
                    .as("with maxConcurrentCalls=2, at least one of 3 simultaneous calls must be rejected")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            callers.shutdownNow();
        }
    }

    private Product callAfter(CountDownLatch startGate) throws InterruptedException {
        startGate.await();
        return inventoryClient.getProductWithBulkhead("1");
    }
}
