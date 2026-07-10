package com.example.resiliency.order.support;

import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;

/** Shared helpers for building canned downstream responses in the resilience integration tests. */
public final class MockDownstream {

    public static final String PRODUCT_JSON =
            "{\"id\":\"1\",\"name\":\"Wireless Mouse\",\"stock\":42,\"price\":19.99}";

    private MockDownstream() {
    }

    public static MockResponse success() {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(PRODUCT_JSON);
    }

    public static MockResponse successDelayedBy(long amount, TimeUnit unit) {
        return success().setBodyDelay(amount, unit);
    }

    public static MockResponse serverError() {
        return new MockResponse().setResponseCode(500);
    }
}
