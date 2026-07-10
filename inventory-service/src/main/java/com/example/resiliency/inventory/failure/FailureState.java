package com.example.resiliency.inventory.failure;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Mutable, process-wide toggle controlling how inventory-service behaves on the next
 * request(s). Flipped via the /admin/mode endpoint so a caller (order-service, a test,
 * or a human with curl) can force the downstream into a failure mode without restarting it.
 */
@Component
public class FailureState {

    private final AtomicReference<FailureSettings> settings =
            new AtomicReference<>(new FailureSettings(FailureMode.OK, 0L, 0.5));

    public FailureSettings get() {
        return settings.get();
    }

    public void set(FailureSettings newSettings) {
        settings.set(newSettings);
    }

    public record FailureSettings(FailureMode mode, long delayMs, double errorRate) {
    }
}
