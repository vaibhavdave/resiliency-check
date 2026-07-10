package com.example.resiliency.order.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class InventoryClientConfig {

    @Bean
    public RestClient inventoryRestClient(RestClient.Builder builder,
                                           @Value("${inventory.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    /**
     * Dedicated, bounded executor for the TimeLimiter demo. TimeLimiter only makes sense
     * against an async call it can walk away from once the timeout fires - a plain
     * synchronous RestClient call on the request thread can't be "cancelled" that way.
     */
    @Bean
    public Executor inventoryAsyncExecutor() {
        return Executors.newFixedThreadPool(8);
    }
}
